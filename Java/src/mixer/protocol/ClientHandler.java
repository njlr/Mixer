package mixer.protocol;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import mixer.protocol.messages.MessagePlayerInput;
import mixer.protocol.messages.MessageSignature;
import mixer.protocol.messages.MessageTransaction;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public strictfp final class ClientHandler extends SimpleChannelHandler {
	
	private static final Object LOCK = new Object();
	
	private final String sourceAddress;
	private final Set<String> targetAddresses;
	
	private final AtomicBoolean signatureLock;
	
	public ClientHandler(final String sourceAddress, final Set<String> targetAddresses) {
		
		super();
		
		this.sourceAddress = sourceAddress;
		this.targetAddresses = ImmutableSet.copyOf(targetAddresses);
		
		this.signatureLock = new AtomicBoolean(false);
	}
	
	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		
		super.channelConnected(ctx, e);
		
		System.out.println("Connected to host. ");
		
		e.getChannel().write(new MessagePlayerInput(this.sourceAddress, this.targetAddresses));
		
		System.out.println("Sent input to host. ");
	}
	
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		
		super.messageReceived(ctx, e);
		
		if (e.getMessage() instanceof MessageTransaction) {
			
			synchronized (LOCK) {
				
				System.out.println("Received a partial transaction from the host. ");
				
				MessageTransaction m = (MessageTransaction) e.getMessage();
				
				Preconditions.checkState(this.isFair(m.getTransaction()), "The transaction is not fair! ");
				
				e.getChannel().write(new MessageSignature(this.getSignature(m.getTransaction())));
			}
		}
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
		
		System.err.println("There was an exception! ");
		System.err.println("Cause: " + e.getCause().getMessage());
	}
	
	private boolean isFair(String transaction) {
		
		return true;
	}
	
	private String getSignature(final String transaction) {
		
		System.out.println("Signing: ");
		System.out.println(transaction.toString());
		
		Preconditions.checkState(!this.signatureLock.getAndSet(true), "A transaction has already been signed. Signing may only occur once for security reasons. ");
		
		return this.sourceAddress + "_SIG";
	}
}
