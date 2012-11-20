package mixer.net;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ClassResolvers;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;

import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;


public strictfp final class Client extends AbstractExecutionThreadService {
	
	private int port;
	
	private boolean keepRunning;
	
	private ClientBootstrap bootstrap;
	private ServerDiscoveryService serverDiscoveryService;
	
	public Client(int port) {
		
		super();
		
		this.port = port;
	}
	
	public Client() {
		
		this(0);
	}
	
	@Override
	protected void startUp() throws Exception {
		
		super.startUp();
		
		this.keepRunning = true;
		
		this.bootstrap = new ClientBootstrap(
				new NioClientSocketChannelFactory(
						Executors.newCachedThreadPool(), 
						Executors.newCachedThreadPool()));
		
		this.bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			public ChannelPipeline getPipeline() throws Exception {
				ChannelPipeline pipeline = Channels.pipeline();
				
				pipeline.addLast("Encoder", new ObjectEncoder());
				pipeline.addLast("Decoder", new ObjectDecoder(ClassResolvers.cacheDisabled(getClass().getClassLoader())));
				pipeline.addLast("Logic", new ClientHandler());
				
				return pipeline;
				}
			});
		
		this.bootstrap.bind(new InetSocketAddress(this.port));
		
		this.serverDiscoveryService = new LocalServerDiscoveryService();
		
		Futures.addCallback(
				this.serverDiscoveryService.getServerSocketAddress(), 
				new FutureCallback<SocketAddress>() {

					@Override
					public void onFailure(Throwable throwable) {
						
						throwable.printStackTrace();
						
						Client.this.triggerShutdown();
					}

					@Override
					public void onSuccess(SocketAddress socketAddress) {
						
						// Client.this.bootstrap.connect(socketAddress); TODO
						Client.this.bootstrap.connect(new InetSocketAddress("localhost", 12345));
					}					
				}, 
				Executors.newCachedThreadPool());
		
		this.serverDiscoveryService.start();
	}
	
	@Override
	public void run() {
		
		while (this.keepRunning) {
			
		}
	}
	
	@Override
	protected void triggerShutdown() {
		
		super.triggerShutdown();
		
		this.keepRunning = false;
	}
	
	@Override
	protected void shutDown() throws Exception {
		
		super.shutDown();
		
		this.serverDiscoveryService.stop();
		
		this.bootstrap.releaseExternalResources();
		
		this.serverDiscoveryService.stopAndWait();
	}
	
	public static void main(String[] args) {
		
		Client client = new Client();
		
		client.start();
	}
}
