package mixer.protocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

public strictfp final class Test {
	
	private Test() {
		
		super();
	}
	
	public static void main(String[] args) throws UnknownHostException {
		
		Host host = new Host(3);
		
		host.start(1234);
		
		SocketAddress hostAddress = new InetSocketAddress(InetAddress.getLocalHost(), 1234);
		
		Set<String> freshAddresses = new HashSet<String>(3);
		
		freshAddresses.add("BOB_FRESH");
		freshAddresses.add("ALICE_FRESH");
		freshAddresses.add("CHARLES_FRESH");
		
		Client clientA = new Client("ALICE_OLD", freshAddresses);
		Client clientB = new Client("BOB_OLD", freshAddresses);
		Client clientC = new Client("CHARLES_OLD", freshAddresses);
		
		clientA.start(hostAddress);
		clientB.start(hostAddress);
		clientC.start(hostAddress);
	}
}
