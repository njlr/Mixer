package mixer.protocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

import mixer.protocol.messages.MessagePlayerInput;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ClassResolvers;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;

public strictfp final class Host extends SimpleChannelHandler {
	
	private static final Object LOCK = new Object();
	
	private final int numberOfPlayers;
	
	private final ServerBootstrap serverBootstrap;
	
	private final ChannelGroup channelGroup;
	
	private final Set<String> oldAddresses;
	private final Set<String> freshAddresses;
	
	private String transaction;
	
	public Host(int numberOfPlayers) {
		
		super();
		
		this.numberOfPlayers = numberOfPlayers;
		
		this.serverBootstrap = new ServerBootstrap(
				new NioServerSocketChannelFactory(
						Executors.newCachedThreadPool(),
						Executors.newCachedThreadPool()));
		
		this.serverBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			
			@Override
			public ChannelPipeline getPipeline() throws Exception {
				
				ChannelPipeline pipeline = Channels.pipeline();
				
				// TODO: SSL?
				
				pipeline.addLast("ObjectEncoder", new ObjectEncoder());
				pipeline.addLast("ObjectDecoder", new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(ClassLoader.getSystemClassLoader())));
				
				pipeline.addLast("Host", Host.this);
				
				return pipeline;
			}
		});
		
		this.channelGroup = new DefaultChannelGroup("Clients");
		
		this.oldAddresses = new HashSet<String>();
		this.freshAddresses = new HashSet<String>();
	}
	
	public void start(int port) throws UnknownHostException {
		
		this.serverBootstrap.bind(new InetSocketAddress(InetAddress.getLocalHost(), port));
	}
	
	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		
		super.channelConnected(ctx, e);
		
		synchronized (LOCK) {
			
			if (this.channelGroup.size() < this.numberOfPlayers) {
				
				this.channelGroup.add(e.getChannel());
			}
			else {
				
				e.getChannel().disconnect();
			}
		}
	}
	
	@Override
	public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		
		super.channelDisconnected(ctx, e);
		
		synchronized (LOCK) {
			
			this.channelGroup.remove(e.getChannel());
		}
	}
	
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		
		super.messageReceived(ctx, e);
		
		if (e.getMessage() instanceof MessagePlayerInput) {
			
			synchronized (LOCK) {
				
				MessagePlayerInput m = (MessagePlayerInput) e.getMessage();
				
				this.addOldAddress(m.getOldAddress());
				this.addFreshAddresses(m.getFreshAddresses());
				
				if ((this.oldAddresses.size() == this.numberOfPlayers) && 
						(this.freshAddresses.size() == this.numberOfPlayers)) {
					
					this.transaction = "1 BTC FROM ";
					
					for (String i : this.oldAddresses) {
						
						this.transaction += i + " ";
					}
					
					this.transaction += "TO ";
					
					for (String i : this.freshAddresses) {
						
						this.transaction += i + " ";
					}
					
					System.out.println("Got transaction: " + this.transaction);
					
					this.channelGroup.write(this.transaction);
				}
			}
		}
	}
	
	private void addOldAddress(String oldAddress) throws Exception {
		
		if (this.oldAddresses.contains(oldAddress)) {
			
			throw new Exception("Already have old address! ");
		}
		
		if (this.oldAddresses.size() == this.numberOfPlayers) {
			
			throw new Exception("Old address list is already at capacity! ");
		}
		
		this.oldAddresses.add(oldAddress);
	}
	
	private void addFreshAddresses(Set<String> freshAddresses) throws Exception {
		
		synchronized (LOCK) {
			
			if (this.freshAddresses.isEmpty()) {
				
				if (freshAddresses.size() == this.numberOfPlayers) {
					
					this.freshAddresses.addAll(freshAddresses);
				}
				else {
					
					throw new Exception("Incorrect number of fresh addresses! ");
				}
			}
			else {
				
				if (!this.freshAddresses.equals(freshAddresses)) {
					
					throw new Exception("Fresh address mismatch! ");
				}
			}
		}
	}
}
