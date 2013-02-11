package mixer.protocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import mixer.MixerUtils;
import mixer.protocol.messages.MessageTransaction;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ClassResolvers;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;
import org.jboss.netty.util.internal.ConcurrentHashMap;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractIdleService;

public strictfp final class Host extends AbstractIdleService {
	
	private static final Object LOCK = new Object();
	
	private final int port;
	private final int playerCount;
	
	private AtomicInteger index;
	
	private final Map<Integer, String> sourceAddresses;
	private final Map<Integer, Set<String>> targetAddresses;
	
	private final Map<Integer, String> signatures;
	
	private String transaction;
	
	private final ChannelGroup channelGroup;
	
	private final ExecutorService bossExecutor;
	private final ExecutorService workerExecutor;
	
	private final ServerBootstrap bootstrap;
	
	private AtomicBoolean startUpLock;
	
	private Channel serverChannel;
	
	public Host(final int port, final int playerCount) {
		
		super();
		
		this.port = port;
		this.playerCount = playerCount;
		
		this.index = new AtomicInteger(0);
		
		this.sourceAddresses = new ConcurrentHashMap<Integer, String>(this.playerCount);
		this.targetAddresses = new ConcurrentHashMap<Integer, Set<String>>(this.playerCount);
		
		this.signatures = new ConcurrentHashMap<Integer, String>(this.playerCount);
		
		this.transaction = null;
		
		this.channelGroup = new DefaultChannelGroup("Players");
		
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
						
						if (index.get() >= playerCount) {
							
							throw new Exception("Too many connection attempts! ");
						}
						
						ChannelPipeline pipeline = Channels.pipeline();
						
						pipeline.addLast("ObjectEncoder", new ObjectEncoder());
						pipeline.addLast("ObjectDecoder", new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(ClassLoader.getSystemClassLoader())));
						
						pipeline.addLast("HostHandler", new HostHandler(
								index.getAndIncrement(), 
								channelGroup, 
								new InputReceivedHandler() {
									
									@Override
									public void inputReceived(int index, String sourceAddress, Set<String> targetAddresses) throws Exception {
										
										synchronized (LOCK) {
											
											Preconditions.checkArgument(index >= 0, "Unexpected index (" + index + ")");
											Preconditions.checkArgument(index < playerCount, "Unexpected index (" + index + ")");
											
											Preconditions.checkArgument(!Host.this.sourceAddresses.containsKey(index));
											Preconditions.checkArgument(!Host.this.targetAddresses.containsKey(index));
											
											Host.this.sourceAddresses.put(index, sourceAddress);
											Host.this.targetAddresses.put(index, targetAddresses);
											
											Preconditions.checkState(MixerUtils.isUniform(Host.this.targetAddresses.values()), "Players do not agree on the target addresses. ");
											
											if (sourceAddresses.size() == playerCount) {
												
												constructAndBroadcastTransaction();
											}
										}
									}
								}, 
								new SignatureReceivedHandler() {
									
									@Override
									public void signatureReceived(int index, String signature) {
										
										synchronized (LOCK) {
											
											Preconditions.checkArgument(index >= 0, "Unexpected index (" + index + ")");
											Preconditions.checkArgument(index < playerCount, "Unexpected index (" + index + ")");;
											
											Preconditions.checkArgument(!signatures.containsKey(index), "A signature has already been received from this player. ");
											
											signatures.put(index, signature);
											
											if (signatures.size() == playerCount) {
												
												finishAndOutputTransaction();
											}
										}
									}
								}));
						
						return pipeline;
					}
				});
		
		this.startUpLock = new AtomicBoolean(false);
	}
	
	@Override
	protected void startUp() throws Exception {
		
		Preconditions.checkState(!this.startUpLock.getAndSet(true), "This host has already been used. ");
		
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
		
		System.out.println("Constructing the transaction... ");
		
		Preconditions.checkState(this.transaction == null, "We have already got a transaction! ");
		
		Preconditions.checkState(!this.targetAddresses.isEmpty(), "There are no target addresses. ");
		Preconditions.checkState(MixerUtils.isUniform(this.targetAddresses.values()), "Players do not agree on the target addresses. ");
		
		final String nl = System.getProperty("line.separator");
		final String tb = "\t";
		
		this.transaction = "INPUTS {" + nl;
		
		for (Entry<Integer, String> i : this.sourceAddresses.entrySet()) {
			
			this.transaction += tb + i.getValue() + nl;
		}
		
		this.transaction += "}" + nl;
		
		this.transaction += "OUTPUTS {" + nl;
		
		for (String i : this.targetAddresses.get(0)) {
			
			this.transaction += tb + i + nl;
		}
		
		this.transaction += "}" + nl;
		
		System.out.println("Broadcasting the transaction... ");
		
		this.channelGroup.write(new MessageTransaction(this.transaction));
	}
	
	private void finishAndOutputTransaction() {
		
		System.out.println("Finishing the transaction... ");
		
		Preconditions.checkState(this.transaction != null, "There is no partial transaction to finish. ");
		
		final String nl = System.getProperty("line.separator");
		final String tb = "\t";
		
		this.transaction += "SIGNATURES {" + nl;
		
		for (String i : this.signatures.values()) {
			
			this.transaction += tb + i + nl;
		}
		
		this.transaction += "}" + nl;
		
		System.out.println("The finished transaction: ");
		
		System.out.println(this.transaction.toString());
		
		this.stop();
	}
}
