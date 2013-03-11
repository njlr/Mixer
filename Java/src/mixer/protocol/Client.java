package mixer.protocol;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import mixer.MixerUtils;
import mixer.protocol.messages.MessagePartialTransaction;
import mixer.protocol.messages.MessagePlayerInput;
import mixer.protocol.messages.MessageSignature;
import mixer.protocol.messages.MessageTransaction;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ClassResolvers;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AbstractExecutionThreadService;

public strictfp final class Client extends AbstractExecutionThreadService {
	
	private static final Object LOCK = new Object();
	
	private final File walletFile;
	private final Wallet wallet;
	
	private final Set<Address> targetAddresses;
	private final BigInteger amount;
	
	private final SocketAddress hostAddress;
	
	private final TransactionOutput source;
	
	private final ExecutorService bossExecutor;
	private final ExecutorService workerExecutor;
	
	private final ClientBootstrap bootstrap;
	
	private AtomicBoolean startUpLock;
	
	private Channel clientChannel;
	
	private final AtomicBoolean keepRunning;
	private final Object lock;
	
	public Client(final File walletFile, final BigInteger amount, final SocketAddress hostAddress, final Set<Address> targetAddresses) throws IOException {
		
		super();
		
		Preconditions.checkArgument(walletFile != null);
		Preconditions.checkArgument(targetAddresses != null);
		Preconditions.checkArgument(Preconditions.checkNotNull(amount).compareTo(BigInteger.ZERO) > 0);
		
		this.walletFile = walletFile;
		
		this.wallet = Wallet.loadFromFile(this.walletFile);
		
		this.targetAddresses = ImmutableSet.copyOf(targetAddresses);
		this.amount = amount;
		
		this.hostAddress = hostAddress;
		
		this.source = Preconditions.checkNotNull(MixerUtils.getClosestOutput(this.wallet, this.amount));
		
		this.bossExecutor = Executors.newCachedThreadPool();
		this.workerExecutor = Executors.newCachedThreadPool();
		
		this.bootstrap = new ClientBootstrap(
				new NioClientSocketChannelFactory(
						this.bossExecutor, 
						this.workerExecutor));
		
		this.bootstrap.setPipelineFactory(
				new ChannelPipelineFactory() {
					
					@Override
					public ChannelPipeline getPipeline() throws Exception {
						
						ChannelPipeline pipeline = Channels.pipeline();
						
						pipeline.addLast("ObjectEncoder", new ObjectEncoder());
						pipeline.addLast("ObjectDecoder", new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(ClassLoader.getSystemClassLoader())));
						
						pipeline.addLast("ClientHandler", new ClientHandler());
						
						return pipeline;
					}
				});
		
		this.startUpLock = new AtomicBoolean(false);
		
		this.keepRunning = new AtomicBoolean(true);
		
		this.lock = new Object();
	}
	
	@Override
	protected void startUp() throws Exception {
		
		super.startUp();
		
		Preconditions.checkState(!this.startUpLock.getAndSet(true), "This client has already been used. ");
		
		this.clientChannel = this.bootstrap.connect(this.hostAddress).awaitUninterruptibly().getChannel();
	}
	
	@Override
	protected void run() throws Exception {
		
		while (this.keepRunning.get()) {
			
			try {
				
				synchronized (lock) {
					
					this.lock.wait();
				}
			}
			catch (InterruptedException e) {
				
			}
		}
	}
	
	@Override
	protected void triggerShutdown() {
		
		super.triggerShutdown();
		
		this.keepRunning.set(false);
		
		synchronized (lock) {
			
			this.lock.notifyAll();
		}
	}
	
	@Override
	protected void shutDown() throws Exception {
		
		super.shutDown();
		
		System.out.println("Shutting down... ");
		
		this.clientChannel.close().awaitUninterruptibly();
		
		this.bossExecutor.shutdown();
		this.workerExecutor.shutdown();
		
		this.bootstrap.releaseExternalResources();
	}
	
	private boolean checkFairness(final Transaction transaction) throws Exception {
		
		synchronized (LOCK) {
			
			Preconditions.checkArgument(transaction != null);
			
			final Set<Address> addresses = new HashSet<Address>(this.targetAddresses);
			
			for (final TransactionOutput i : transaction.getOutputs()) {
				
				if (!i.getValue().equals(this.amount)) {
					
					System.out.println("Outputs must all receive the same amount! ");
					
					return false;
				}
				
				if (i.getScriptPubKey().isSentToAddress()) {
					
					final Address address = i.getScriptPubKey().getToAddress();
					
					if (addresses.contains(address)) {
						
						addresses.remove(address);
					}
					else {
						
						System.out.println("Unexpected output address " + address + "! ");
						
						return false;
					}
				}
				else {
					
					System.out.println("Payout must be to an address. ");
					
					return false;
				}
			}
			
			if (addresses.isEmpty()) {
				
				return true; // TODO: Check inputs?
			}
			else {
				
				System.out.println("The following are not paid: " + addresses.toString() + "! ");
				
				return false;
			}
		}
	}
	
	private strictfp final class ClientHandler extends SimpleChannelHandler {
		
		private final AtomicBoolean signatureLatch;
		
		public ClientHandler() {
			
			super();
			
			this.signatureLatch = new AtomicBoolean(true);
		}
		
		@Override
		public void channelConnected(final ChannelHandlerContext ctx, final ChannelStateEvent e) throws Exception {
			
			super.channelConnected(ctx, e);
			
			System.out.println("Connected to host. ");
			
			e.getChannel().write(new MessagePlayerInput(source, targetAddresses));
			
			System.out.println("Sent input to host. ");
		}
		
		@Override
		public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent e) throws Exception {
			
			super.messageReceived(ctx, e);
			
			if (e.getMessage() instanceof MessagePartialTransaction) {
				
				synchronized (LOCK) {
					
					System.out.println("Received a partial transaction from the host. ");
					
					final MessagePartialTransaction m = (MessagePartialTransaction) e.getMessage();
					
					Preconditions.checkState(checkFairness(m.getTransaction()), "The transaction is not fair! ");
					
					e.getChannel().write(new MessageSignature(this.getSignature(m.getTransaction(), m.getIndexToSign())));
				}
			}
			else if (e.getMessage() instanceof MessageTransaction) {
				
				synchronized (LOCK) {
					
					System.out.println("Received the finished transaction. ");
					
					final MessageTransaction m = (MessageTransaction) e.getMessage();
					
					System.out.println("The finished transaction: ");
					System.out.println(m.getTransaction().toString());
					
					wallet.commitTx(m.getTransaction());
					wallet.saveToFile(walletFile);
					
					stop();
				}
			}
		}
		
		@Override
		public void exceptionCaught(final ChannelHandlerContext ctx, final ExceptionEvent e) throws Exception {
			
			System.err.println("There was an exception! ");
			System.err.println("Cause: " + e.getCause().getMessage());
			
			e.getCause().printStackTrace();
		}
		
		private byte[] getSignature(final Transaction transaction, final int index) {
			
			System.out.println("Signing index " + index + " of: ");
			System.out.println(transaction.toString());
			
			Preconditions.checkState(
					this.signatureLatch.getAndSet(false), 
					"A transaction has already been signed. Signing may only occur once for security reasons. ");
			
			try {
				
				return transaction.computeScriptBytes(index, SigHash.ALL, wallet);
			}
			catch (final ScriptException e) {
				
				return null;
			}
		}
	}
}
