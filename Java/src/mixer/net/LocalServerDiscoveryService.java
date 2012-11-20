package mixer.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.Executors;

import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;


/**
 * Discovers servers on the local network. 
 * Will send discovery requests to each port and IP address!
 * 
 * @author Nick La Rooy
 *
 */
public strictfp final class LocalServerDiscoveryService extends AbstractExecutionThreadService implements ServerDiscoveryService {

	private boolean keepRunning;
	
	private DatagramSocket datagramSocket;
	
	private SettableFuture<SocketAddress> serverSocketAddress;
	
	@Override
	public ListenableFuture<SocketAddress> getServerSocketAddress() {
		
		return this.serverSocketAddress;
	}
	
	public LocalServerDiscoveryService() {
		
		super();
		
		this.serverSocketAddress = SettableFuture.create();
	}
	
	@Override
	protected void startUp() throws Exception {
		
		super.startUp();
		
		this.keepRunning = true;
		
		this.datagramSocket = new DatagramSocket();
		
		this.datagramSocket.setBroadcast(true);
		this.datagramSocket.setSoTimeout(5000);
	}
	
	@Override
	protected void run() {
		
		while (this.keepRunning) {
				
			for (int i = 1025; (this.keepRunning) && (i < 65535); i++) {
				
				try {
					
					datagramSocket.send(
							new DatagramPacket(
									Messages.DISCOVERY_REQUEST,  
									Messages.DISCOVERY_REQUEST.length, 
									InetAddress.getByName("255.255.255.255"), 
									i));
					
				} 
				catch (UnknownHostException e) {
					
					e.printStackTrace();
				} 
				catch (IOException e) {
					
					e.printStackTrace();
				}
			}
			
			// TODO: Create listener before sending any packets
			try {
				
				DatagramPacket response = new DatagramPacket(
						new byte[Messages.DISCOVERY_RESPONSE.length], 
						Messages.DISCOVERY_RESPONSE.length);
				
				datagramSocket.receive(response);
				
				if (Arrays.equals(response.getData(), Messages.DISCOVERY_RESPONSE)) {
					
					this.serverSocketAddress.set(response.getSocketAddress());
					
					this.stop();
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
	}
	
	public static void main(String[] args) {
		
		LocalServerDiscoveryService localServerDiscoveryService = new LocalServerDiscoveryService();
		
		Futures.addCallback(
				localServerDiscoveryService.getServerSocketAddress(), 
				new FutureCallback<SocketAddress>() {

					@Override
					public void onFailure(Throwable throwable) {
						
						throwable.printStackTrace();
					}

					@Override
					public void onSuccess(SocketAddress socketAddress) {
						
						System.out.println(socketAddress.toString());
					}
				}, 
				Executors.newCachedThreadPool());
		
		localServerDiscoveryService.startAndWait();
	}
}
