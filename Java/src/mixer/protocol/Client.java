package mixer.protocol;

import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.Executors;

import mixer.protocol.messages.MessagePlayerInput;
import mixer.protocol.messages.MessageSignature;
import mixer.protocol.messages.MessageTransaction;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ClassResolvers;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;

public strictfp final class Client extends SimpleChannelHandler {
	
	private static final Object LOCK = new Object();
	
	private final ClientBootstrap clientBootstrap;
	
	private final String oldAddress;
	private final Set<String> freshAddresses;
	
	private boolean signingLock;
	
	public Client(String oldAddress, Set<String> freshAddresses) {
		 
		 super();
		 
		 this.oldAddress = oldAddress;
		 this.freshAddresses = freshAddresses;
		 
		 this.clientBootstrap = new ClientBootstrap(
				 new NioClientSocketChannelFactory(
						 Executors.newCachedThreadPool(), 
						 Executors.newCachedThreadPool()));
		 
		 this.clientBootstrap.setPipelineFactory(
				 new ChannelPipelineFactory() {
					
					@Override
					public ChannelPipeline getPipeline() throws Exception {
						
						ChannelPipeline pipeline = Channels.pipeline();
						
						// TODO: SSL?
						
						pipeline.addLast("ObjectEncoder", new ObjectEncoder());
						pipeline.addLast("ObjectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(ClassLoader.getSystemClassLoader())));
						
						pipeline.addLast("Client", Client.this);
						
						return pipeline;
					}
				});
		 
		 this.signingLock = false;
	}
	
	public void start(SocketAddress hostAddress) {
		
		this.clientBootstrap.connect(hostAddress);
	}
	
	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		
		super.channelConnected(ctx, e);
		
		e.getChannel().write(new MessagePlayerInput(this.oldAddress, this.freshAddresses));
	}
	
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		
		super.messageReceived(ctx, e);
		
		if (e.getMessage() instanceof MessageTransaction) {
			
			synchronized (LOCK) {
				
				if (!this.signingLock) {
					
					this.signingLock = true;
					
					MessageTransaction m = (MessageTransaction) e.getMessage();
					
					if (this.isTransactionFair(m.getTransaction())) {
						
						String signature = this.signTransaction(m.getTransaction());
						
						e.getChannel().write(new MessageSignature(signature));
					}
					else {
						
						throw new Exception("Transaction is not fair! ");
					}
				}
			}
		}
	}
	
	private boolean isTransactionFair(String transaction) { // TODO: Transaction
		
		return true; // TODO: Matches Python output
	}
	
	private String signTransaction(String transaction) { // TODO: Transaction // TODO: Signature
		
		return "SIGNED(" + this.oldAddress + ", " + transaction.hashCode() + ")"; // TODO
	}
}
