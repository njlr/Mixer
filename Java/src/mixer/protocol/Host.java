package mixer.protocol;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import mixer.MixerUtils;
import mixer.protocol.messages.MessagePartialTransaction;
import mixer.protocol.messages.MessagePlayerInput;
import mixer.protocol.messages.MessageSignature;
import mixer.protocol.messages.MessageTransaction;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ClassResolvers;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionOutput;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractIdleService;
//TODO: Proper shutdown
public strictfp final class Host extends AbstractIdleService {
	
	private static final Object LOCK = new Object();
	
	private final NetworkParameters networkParameters;
	private final BigInteger amount;
	
	private final int port;
	private final int playerCount;
	
	private final AtomicInteger index;
	
	private final SortedMap<Integer, TransactionOutput> sourceAddresses;
	private final SortedMap<Integer, Set<Address>> targetAddresses;
	
	private final Set<HostHandler> hostHandlers;
	
	private final SortedMap<Integer, byte[]> signatures;
	
	private final ExecutorService bossExecutor;
	private final ExecutorService workerExecutor;
	
	private final ServerBootstrap bootstrap;
	
	private final AtomicBoolean startUpLock;
	
	private Transaction transaction;
	
	private Channel serverChannel;
	
	public Host(final NetworkParameters networkParameters, final BigInteger amount, final int port, final int playerCount) {
		
		super();
		
		this.networkParameters = networkParameters;
		this.amount = amount;
		
		this.port = port;
		this.playerCount = playerCount;
		
		this.index = new AtomicInteger(0);
		
		this.sourceAddresses = new ConcurrentSkipListMap<Integer, TransactionOutput>();
		this.targetAddresses = new ConcurrentSkipListMap<Integer, Set<Address>>();
		
		this.signatures = new ConcurrentSkipListMap<Integer, byte[]>();
		
		this.transaction = null;
		
		this.hostHandlers = new HashSet<Host.HostHandler>();
		
		this.bossExecutor = Executors.newCachedThreadPool();
		this.workerExecutor = Executors.newCachedThreadPool();
		
		this.bootstrap = new ServerBootstrap(
				new NioServerSocketChannelFactory(
						this.bossExecutor, 
						this.workerExecutor));
		
		this.bootstrap.setPipelineFactory(
				new ChannelPipelineFactory() {
					
					@Override
					public ChannelPipeline getPipeline() throws Exception {
						
						final int currentIndex = index.getAndIncrement();
						
						if (currentIndex >= playerCount) {
							
							throw new Exception("Too many connection attempts! ");
						}
						
						ChannelPipeline pipeline = Channels.pipeline();
						
						pipeline.addLast("ObjectEncoder", new ObjectEncoder());
						pipeline.addLast("ObjectDecoder", new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(ClassLoader.getSystemClassLoader())));
						
						pipeline.addLast("HostHandler", new HostHandler(currentIndex));
						
						return pipeline;
					}
				});
		
		this.startUpLock = new AtomicBoolean(true);
	}
	
	@Override
	protected void startUp() throws Exception {
		
		Preconditions.checkState(this.startUpLock.getAndSet(false), "This host has already been used. ");
		
		this.serverChannel = this.bootstrap.bind(
				new InetSocketAddress(
						InetAddress.getLocalHost(), 
						this.port));
	}
	
	@Override
	protected void shutDown() throws Exception {
		
		System.out.println("Shutting down... ");
		
		this.serverChannel.close().awaitUninterruptibly();
		
		this.bossExecutor.shutdown();
		this.workerExecutor.shutdown();
		
		this.bootstrap.releaseExternalResources();
	}
	
	private void constructAndBroadcastTransaction() {
		
		synchronized (LOCK) {
			
			System.out.println("Constructing the transaction... ");
			
			Preconditions.checkState(this.transaction == null, "We have already got a transaction! ");
			
			Preconditions.checkState(!this.targetAddresses.isEmpty(), "There are no target addresses. ");
			Preconditions.checkState(MixerUtils.isUniform(this.targetAddresses.values()), "Players do not agree on the target addresses. ");
			
			this.transaction = new Transaction(this.networkParameters);
			
			for (final TransactionOutput i : this.sourceAddresses.values()) {
				
				this.transaction.addInput(i);
			}
			
			for (final Address i : this.targetAddresses.get(0)) { // We can just grab the first because uniformity is checked already
				
				this.transaction.addOutput(this.amount, i);
			}
			
			for (final HostHandler i : this.hostHandlers) {
				
				i.sendPartialTransaction(this.transaction);
			}
		}
	}
	
	private void finishAndOutputTransaction() {
		
		synchronized (LOCK) {
			
			System.out.println("Finishing the transaction... ");
			
			Preconditions.checkState(this.transaction != null, "There is no partial transaction to finish. ");
			
			for (int i = 0; i < this.transaction.getInputs().size(); i++) {
				
				this.transaction.getInput(i).setScriptBytes(this.signatures.get(i));
			}
			
			System.out.println("The finished transaction: ");
			
			System.out.println(this.transaction.toString());
			
			for (final HostHandler i : this.hostHandlers) {
				
				i.sendTransaction(this.transaction);
			}
		}
	}
	
	private strictfp final class HostHandler extends SimpleChannelHandler {
		
		private final int index;
		
		private final AtomicBoolean inputLock;
		private final AtomicBoolean signatureLock;
		
		private Channel channel;
		
		public HostHandler(final int index) {
			
			super();
			
			Preconditions.checkArgument(index >= 0, "Unexpected index (" + index + ")");
			Preconditions.checkArgument(index < playerCount, "Unexpected index (" + index + ")");
			
			this.index = index;
			
			this.inputLock = new AtomicBoolean(true);
			this.signatureLock = new AtomicBoolean(true);
			
			this.channel = null;
		}
		
		@Override
		public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
			
			super.channelConnected(ctx, e);
			
			synchronized (LOCK) {
				
				Preconditions.checkArgument(this.channel == null);
				
				this.channel = e.getChannel();
				
				hostHandlers.add(this);
			}
		}
		
		@Override
		public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
			
			super.messageReceived(ctx, e);
			
			if (e.getMessage() instanceof MessagePlayerInput) {
				
				synchronized (LOCK) {
					
					System.out.println("Received input from player " + this.index + ". ");
					
					Preconditions.checkState(this.inputLock.getAndSet(false), "Input has already been received from this player. ");
					
					MessagePlayerInput m = (MessagePlayerInput) e.getMessage();
					
					Preconditions.checkArgument(!sourceAddresses.containsKey(index));
					Preconditions.checkArgument(!targetAddresses.containsKey(index));
					
					sourceAddresses.put(this.index, m.getSource());
					targetAddresses.put(this.index, m.getTargetAddresses());
					
					Preconditions.checkState(MixerUtils.isUniform(Host.this.targetAddresses.values()), "Players do not agree on the target addresses. ");
					
					if (sourceAddresses.size() == playerCount) {
						
						constructAndBroadcastTransaction();
					}
				}
			}
			else if (e.getMessage() instanceof MessageSignature) {
				
				synchronized (LOCK) {
					
					System.out.println("Received signature from player " + this.index + ". ");
					
					Preconditions.checkState(!this.inputLock.get(), "Input not yet received. ");
					Preconditions.checkState(this.signatureLock.getAndSet(false), "A signature has already been received from this player. ");
					
					Preconditions.checkState(!signatures.containsKey(index), "A signature has already been received from this player. ");
					
					MessageSignature m = (MessageSignature) e.getMessage();
					
					signatures.put(this.index, m.getSignature());
					
					if (signatures.size() == playerCount) {
						
						finishAndOutputTransaction();
					}
				}
			}
		}
		
		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
			
			System.err.println("There was an exception! ");
			System.err.println("Cause: " + e.getCause().getMessage());
			
			e.getCause().printStackTrace();
			
			stop();
		}
		
		public void sendPartialTransaction(final Transaction transaction) {
			
			this.channel.write(new MessagePartialTransaction(transaction, this.index));
		}
		
		public void sendTransaction(final Transaction transaction) {
			
			this.channel.write(new MessageTransaction(transaction));
		}
	}
}
