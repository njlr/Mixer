package mixer.protocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

public strictfp final class Test {
	
	private Test() {
		
		super();
	}
	
	public static void main(String[] args) throws UnknownHostException {
		
		final int port = 1234;
		
		Host host = new Host(port, 3);
		
		host.startAndWait();
		
		InetSocketAddress hostAddress = new InetSocketAddress(InetAddress.getLocalHost(), port);
		
		final Set<String> targetAddresses = new HashSet<String>();
		
		targetAddresses.add("ALICE_TARGET");
		targetAddresses.add("BOB_TARGET");
		targetAddresses.add("CHARLIE_TARGET");
		
		Client clientA = new Client("ALICE_SOURCE", targetAddresses, hostAddress);
		
		clientA.start();
		
		Client clientB = new Client("BOB_SOURCE", targetAddresses, hostAddress);
		
		clientB.start();
		
		Client clientC = new Client("CHARLIE_SOURCE", targetAddresses, hostAddress);
		
		clientC.start();
	}
}
