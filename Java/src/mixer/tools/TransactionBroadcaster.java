package mixer.tools;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import com.google.bitcoin.core.BlockChain;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerAddress;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.discovery.IrcDiscovery;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.store.BoundedOverheadBlockStore;

public strictfp final class TransactionBroadcaster {
	
	private TransactionBroadcaster() {
		
		super();
	}
	
	public static void main(final String[] args) throws IOException, BlockStoreException {
		
		if (args.length != 2) {
			
			System.out.println("Transaction Broadcaster Tool");
			System.out.println("Broadcasts all transactions in the given wallet to the network. ");
			System.out.println("Usage: ");
			System.out.println("0 - BitcoinJ Wallet file");
			System.out.println("1 - BitcoinJ Chain file");
			
			return;
		}
		
		final File walletFile = new File(args[0]);
		final File chainFile = new File(args[1]);
		
		final InetSocketAddress tpAddress = InetSocketAddress.createUnresolved("54.243.211.176", 18333);
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
		wallet.autosaveToFile(walletFile, 200, TimeUnit.MILLISECONDS, null);
		
		final NetworkParameters networkParameters = wallet.getNetworkParameters();
		
		final BlockStore blockStore = new BoundedOverheadBlockStore(networkParameters, chainFile);
		final BlockChain blockChain = new BlockChain(networkParameters, wallet, blockStore);
		
		final PeerGroup peerGroup = new PeerGroup(networkParameters, blockChain);
		
		peerGroup.setMinBroadcastConnections(1);
		
		peerGroup.addWallet(wallet);
		peerGroup.addPeerDiscovery(new IrcDiscovery("#bitcoinTEST3"));
		
		peerGroup.startAndWait();
		
		peerGroup.addAddress(new PeerAddress(tpAddress));
		
		for (final Transaction i : wallet.getPendingTransactions()) {
			
			try {
				
				peerGroup.broadcastTransaction(i).get();
				
				System.out.println("Success!");
				System.out.println(i);
			} 
			catch (Exception e) {
				
				e.printStackTrace();
			}
		}
		
		peerGroup.stop();
	}
}
