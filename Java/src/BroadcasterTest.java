import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.bitcoin.core.BlockChain;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletTransaction;
import com.google.bitcoin.core.WalletTransaction.Pool;
import com.google.bitcoin.discovery.IrcDiscovery;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.store.BoundedOverheadBlockStore;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

public strictfp final class BroadcasterTest {
	
	private BroadcasterTest() {
		
		super();
	}
	
	public static void main(String[] args) throws IOException, BlockStoreException {
		
		final File walletFile = new File("./lib/test.wallet");
		final File chainFile = new File("./lib/testnet.chain");
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
		wallet.autosaveToFile(walletFile, 200, TimeUnit.MILLISECONDS, null);
		
		final NetworkParameters networkParameters = wallet.getNetworkParameters();
		
		final BlockStore blockStore = new BoundedOverheadBlockStore(networkParameters, chainFile);
		final BlockChain blockChain = new BlockChain(networkParameters, wallet, blockStore);
		
		final PeerGroup peerGroup = new PeerGroup(networkParameters, blockChain);
		
		peerGroup.setMinBroadcastConnections(2);
		
		peerGroup.addWallet(wallet);
		peerGroup.addPeerDiscovery(new IrcDiscovery("#bitcoinTEST3"));
		
		peerGroup.startAndWait();
		
		final AtomicInteger count = new AtomicInteger(0);
		
		for (WalletTransaction i : wallet.getWalletTransactions()) {
			
			if (i.getPool() == Pool.PENDING) {
				
				count.incrementAndGet();
				
				Futures.addCallback(
						peerGroup.broadcastTransaction(i.getTransaction()), 
						new FutureCallback<Transaction>() {
							
							@Override
							public void onSuccess(Transaction transaction) {
								
								System.out.println("Success: " + transaction.toString());
								
								if (count.decrementAndGet() == 0) {
									
									System.out.println("Closing... ");
									
									peerGroup.stopAndWait();
								}
							}
							
							@Override
							public void onFailure(Throwable throwable) {
								
								System.out.println("Failure: " + throwable.getMessage());
								
								if (count.decrementAndGet() == 0) {
									
									System.out.println("Closing... ");
									
									peerGroup.stopAndWait();
								}
							}
						});
			}
		}
	}
}
