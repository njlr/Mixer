package mixer.protocol;

import java.util.concurrent.atomic.AtomicBoolean;

import mixer.protocol.messages.MessagePlayerInput;
import mixer.protocol.messages.MessageSignature;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;

import com.google.common.base.Preconditions;

public strictfp final class HostHandler extends SimpleChannelHandler {
	
	private static final Object LOCK = new Object();
	
	private final int index;
	
	private final ChannelGroup channelGroup;
	
	private final InputReceivedHandler inputReceivedHandler;
	private final SignatureReceivedHandler signatureReceivedHandler;
	
	private final AtomicBoolean playerInputLock;
	private final AtomicBoolean signatureLock;
	
	public HostHandler(final int index, final ChannelGroup channelGroup, final InputReceivedHandler inputReceivedHandler, final SignatureReceivedHandler signatureReceivedHandler) {
		
		super();
		
		this.index = index;
		
		this.channelGroup = channelGroup;
		
		this.inputReceivedHandler = inputReceivedHandler;
		this.signatureReceivedHandler = signatureReceivedHandler;
		
		this.playerInputLock = new AtomicBoolean(false);
		this.signatureLock = new AtomicBoolean(false);
	}
	
	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		
		super.channelConnected(ctx, e);
		
		this.channelGroup.add(e.getChannel());
	}
	
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		
		super.messageReceived(ctx, e);
		
		if (e.getMessage() instanceof MessagePlayerInput) {
			
			synchronized (LOCK) {
				
				System.out.println("Received input from player " + this.index + ". ");
				
				Preconditions.checkState(!this.playerInputLock.getAndSet(true), "Input has already been received from this player. ");
				
				MessagePlayerInput m = (MessagePlayerInput) e.getMessage();
				
				this.inputReceivedHandler.inputReceived(
						this.index, 
						m.getSourceAddress(), 
						m.getTargetAddresses());
			}
		}
		else if (e.getMessage() instanceof MessageSignature) {
			
			synchronized (LOCK) {
				
				System.out.println("Received signature from player " + this.index + ". ");
				
				Preconditions.checkState(!this.signatureLock.getAndSet(true), "A signature has already been received from this player. ");
				
				MessageSignature m = (MessageSignature) e.getMessage();
				
				this.signatureReceivedHandler.signatureReceived(
						this.index, 
						m.getSignature());
			}
		}
	}
	
	@Override
	public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		
		super.channelDisconnected(ctx, e);
		
		this.channelGroup.remove(e.getChannel());
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
		
		System.err.println("There was an exception! ");
		System.err.println("Cause: " + e.getCause().getMessage());
	}
}
