package mixer.protocol;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ClassResolvers;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AbstractExecutionThreadService;

public strictfp final class Client extends AbstractExecutionThreadService {
	
	private final String sourceAddress; 
	private final Set<String> targetAddresses;
	
	private final InetSocketAddress hostAddress;
	
	private final ExecutorService bossExecutor;
	private final ExecutorService workerExecutor;
	
	private final ClientBootstrap bootstrap;
	
	private AtomicBoolean startUpLock;
	
	private Channel clientChannel;
	
	private final AtomicBoolean keepRunning;
	private final Object lock;
	
	public Client(final String sourceAddress, final Set<String> targetAddresses, final InetSocketAddress hostAddress) {
		
		super();
		
		this.sourceAddress = sourceAddress;
		this.targetAddresses = ImmutableSet.copyOf(targetAddresses);
		
		this.hostAddress = hostAddress;
		
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
						
						pipeline.addLast("ClientHandler", new ClientHandler(Client.this.sourceAddress, Client.this.targetAddresses));
						
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
}
