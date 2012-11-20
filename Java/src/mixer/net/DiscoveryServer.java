package mixer.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;

import com.google.common.util.concurrent.AbstractExecutionThreadService;


/**
 * A simple service for responding to discovery requests. 
 * Run this in parallel with a server to allow clients to 
 * find that server. 
 * 
 * @author Nick La Rooy
 *
 */
public strictfp final class DiscoveryServer extends AbstractExecutionThreadService {
	
	private SocketAddress serverAddress;
	
	private int port;
	
	private boolean keepRunning;
	
	private DatagramSocket datagramSocket;
	
	public DiscoveryServer(SocketAddress serverAddress, int port) {
		
		super();
		
		this.serverAddress = serverAddress;
		
		this.port = port;
	}
	
	@Override
	protected void startUp() throws Exception {
		
		super.startUp();
		
		this.keepRunning = true;
		
		this.datagramSocket = new DatagramSocket(this.port);
		
		this.datagramSocket.setSoTimeout(1000);
	}
	
	@Override
	protected void run() {
		
		DatagramPacket request = new DatagramPacket(
				new byte[Messages.DISCOVERY_REQUEST.length], 
				Messages.DISCOVERY_REQUEST.length);
		
		while (this.keepRunning) {
			
			try {
				
				this.datagramSocket.receive(request);
				
				if (Arrays.equals(request.getData(), Messages.DISCOVERY_REQUEST)) {
					
					System.out.println("Received a discovery request from " + request.getSocketAddress().toString());
					
					DatagramPacket response = new DatagramPacket(
							Messages.DISCOVERY_RESPONSE, 
							Messages.DISCOVERY_RESPONSE.length, 
							request.getSocketAddress());
					
					// TODO: Include server address and port!
					
					this.datagramSocket.send(response);
				}
			} 
			catch (IOException e) {
				
			}
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
		
		this.datagramSocket.close();
	}
	
	public static void main(String[] args) throws IOException {
		
		if (args.length < 2) {
			
			System.out.println("Tool for launching a discovery server. ");
			System.out.println("Usage: ");
			System.out.println(" 0 - Server Port");
			System.out.println(" 1 - Server Address");
			System.out.println("[2 - Port]");
			
			return;
		}
		
		InetAddress serverAddress = InetAddress.getByName(args[0]);
		
		int serverPort = Integer.parseInt(args[1]);
		
		int port;
		
		if (args.length > 2) {
			
			port = Integer.parseInt(args[2]);
		}
		else {
			
			port = 0;
		}
		
		DiscoveryServer discoveryServer = new DiscoveryServer(
				new InetSocketAddress(serverAddress, serverPort), 
				port);
		
		System.out.println("Starting discovery server... ");
		
		discoveryServer.startAndWait();
		
		System.out.println("Discovery server has started. ");
		System.out.println("Press Enter to shutdown... ");
		
		System.in.read();
		
		System.out.println("Shutting down discovery server... ");
		
		discoveryServer.stopAndWait();
		
		System.out.println("Discovery server shut down. ");
	}
}
