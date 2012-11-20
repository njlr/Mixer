package mixer.net;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

public strictfp final class ClientHandler extends SimpleChannelHandler {
	
	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		
		super.channelConnected(ctx, e);
		
		System.out.println("Client connected");
		System.out.println(e.getChannel().getRemoteAddress().toString());
	}
}
