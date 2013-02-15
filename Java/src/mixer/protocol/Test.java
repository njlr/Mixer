package mixer.protocol;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;

public strictfp final class Test {
	
	private Test() {
		
		super();
	}
	
	public static void main(String[] args) throws IOException {
		
		final File walletFile = new File("./lib/test.wallet");
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
		final NetworkParameters networkParameters = wallet.getNetworkParameters();
		
		final int port = 1234;
		
		final BigInteger amount = Utils.toNanoCoins(0, 10);
		
		Host host = new Host(networkParameters, amount, port, 3);
		
		host.startAndWait();
		
		InetSocketAddress hostAddress = new InetSocketAddress(InetAddress.getLocalHost(), port);
		
		final Set<Address> targetAddresses = new HashSet<Address>();
		
		targetAddresses.add(new ECKey().toAddress(networkParameters));
		targetAddresses.add(new ECKey().toAddress(networkParameters));
		targetAddresses.add(new ECKey().toAddress(networkParameters));
		
		// A
		ECKey ecKeyA = new ECKey();
		
		wallet.addKey(ecKeyA);
		
		Client clientA = new Client(wallet, targetAddresses, amount, hostAddress);
		
		clientA.start();
		
		// B
		ECKey ecKeyB = new ECKey();
		
		wallet.addKey(ecKeyB);
		
		Client clientB = new Client(wallet, targetAddresses, amount, hostAddress);
		
		clientB.start();
		
		// C
		ECKey ecKeyC = new ECKey();
		
		wallet.addKey(ecKeyC);
		
		Client clientC = new Client(wallet, targetAddresses, amount, hostAddress);
		
		clientC.start();
	}
}
