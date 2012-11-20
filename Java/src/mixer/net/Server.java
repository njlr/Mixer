package mixer.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ClassResolvers;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;

import com.google.common.util.concurrent.AbstractExecutionThreadService;


public strictfp final class Server extends AbstractExecutionThreadService {
	
	private int port;
	
	private boolean keepRunning;
	
	private ServerBootstrap bootstrap;
	private DiscoveryServer discoveryServer;
	
	public Server(int port) {
		
		super();
		
		this.port = port;
	}
	
	public Server() throws UnknownHostException {
		
		this(0);
	}
	
	@Override
	protected void startUp() throws Exception {
		
		super.startUp();
		
		this.keepRunning = true;
		
		this.bootstrap = new ServerBootstrap(
				new NioServerSocketChannelFactory(
						Executors.newCachedThreadPool(),
						Executors.newCachedThreadPool()));
		
		this.bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			public ChannelPipeline getPipeline() throws Exception {
				ChannelPipeline pipeline = Channels.pipeline();
				
				pipeline.addLast("Encoder", new ObjectEncoder());
				pipeline.addLast("Decoder", new ObjectDecoder(ClassResolvers.cacheDisabled(getClass().getClassLoader())));
				pipeline.addLast("Logic", new ServerHandler());
				
				return pipeline;
				}
			});
		
		this.bootstrap.bind(new InetSocketAddress(this.port));
		
		this.discoveryServer = new DiscoveryServer(new InetSocketAddress(InetAddress.getLocalHost(), this.port), 0);
		
		this.discoveryServer.start();
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
		
		this.discoveryServer.stop();
		
		this.bootstrap.releaseExternalResources();
		
		this.discoveryServer.stopAndWait();
	}
	
	public static void main(String[] args) {
		
		Server server = new Server(12345);
		
		server.start();
	}
}
