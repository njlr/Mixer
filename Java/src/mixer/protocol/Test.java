package mixer.protocol;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.common.base.Preconditions;

public strictfp final class Test {
	
	private Test() {
		
		super();
	}
	
	public static void main(String[] args) throws IOException {
		
		final File walletFileA = new File("./lib/test.wallet");
		final File walletFileB = new File("./lib/test2.wallet");
		final File walletFileC = new File("./lib/test3.wallet");
		
		final Wallet walletA = Wallet.loadFromFile(walletFileA);
		final Wallet walletB = Wallet.loadFromFile(walletFileB);
		final Wallet walletC = Wallet.loadFromFile(walletFileC);
		
		final NetworkParameters networkParameters = walletA.getNetworkParameters();
		
		Preconditions.checkState(walletB.getNetworkParameters().equals(networkParameters));
		Preconditions.checkState(walletC.getNetworkParameters().equals(networkParameters));
		
		walletA.autosaveToFile(walletFileA, 5, TimeUnit.SECONDS, null);
		walletB.autosaveToFile(walletFileB, 5, TimeUnit.SECONDS, null);
		walletC.autosaveToFile(walletFileC, 5, TimeUnit.SECONDS, null);
		
		final int port = 1234;
		
		final BigInteger amount = Utils.toNanoCoins(0, 13);
		
		Host host = new Host(networkParameters, amount, port, 3);
		
		host.startAndWait();
		
		InetSocketAddress hostAddress = new InetSocketAddress(InetAddress.getLocalHost(), port);
		
		final ECKey ecKeyA = new ECKey();
		final ECKey ecKeyB = new ECKey();
		final ECKey ecKeyC = new ECKey();
		
		final Set<Address> targetAddresses = new HashSet<Address>();
		
		targetAddresses.add(ecKeyA.toAddress(networkParameters));
		targetAddresses.add(ecKeyB.toAddress(networkParameters));
		targetAddresses.add(ecKeyC.toAddress(networkParameters));
		
		// A
		walletA.addKey(ecKeyA);
		
		final Client clientA = new Client(walletA, amount, hostAddress, targetAddresses);
		
		clientA.start();
		
		// B
		walletB.addKey(ecKeyB);
		
		final Client clientB = new Client(walletB, amount, hostAddress, targetAddresses);
		
		clientB.start();
		
		// C
		walletC.addKey(ecKeyC);
		
		final Client clientC = new Client(walletC, amount, hostAddress, targetAddresses);
		
		clientC.start();
	}
}
