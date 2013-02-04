import java.io.File;
import java.io.IOException;
import java.math.BigInteger;

import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.BlockChain;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.Wallet.SendRequest;
import com.google.bitcoin.core.WrongNetworkException;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.store.BoundedOverheadBlockStore;
import com.google.bitcoin.store.DiskBlockStore;

public strictfp final class Test2 {
	
	private Test2() {
		
		super();
	}
	
	public static void main(String[] args) throws WrongNetworkException, AddressFormatException, BlockStoreException {
		
		/*
		 * The addresses: 
		 * mqToJ7eq6TpBiKeAA3CXdSZewUdMV3oXQp
		 * pub:0380b7615ac1e28d3b82a09aacc6586f120946e0a3e19aace5aa72978f89c7e769 
		 * timestamp:1359919802 
		 * priv:00eb29ede8064d278e65155869c4b52c19a0be4dbf446de4f4cc8a3249617e618c
		 * 
		 * mpnnNw13DX9Ub6H8YuALP1ie5v2hpXhYqs
		 * pub:026abd1adeb84c63451f1e8a1c6f06c040d298c1d91f6c709d03515924feaad083 
		 * timestamp:1359983788 
		 * priv:54ce5868141a24fed7d8b298b0d8690a5459cbc224e6f25d77f2dcc32e2ec651
		 */
		
		final NetworkParameters networkParameters = NetworkParameters.testNet();
		
		final BigInteger amount = Utils.toNanoCoins(0, 1);
		
		final File walletFile = new File("./lib/test.wallet");
		final File chainFile = new File("./lib/testnet.chain");
		
		try {
			
			final Wallet wallet = Wallet.loadFromFile(walletFile);
			
			final BlockStore blockStore = new BoundedOverheadBlockStore(networkParameters, chainFile);
			final BlockChain blockChain = new BlockChain(networkParameters, blockStore);
			
			// final PeerGroup peerGroup = new PeerGroup(networkParameters, blockChain);
			
			// peerGroup.startAndWait();
			
			final ECKey keyTarget = wallet.keychain.get(1);
			
			SendRequest sendRequest = SendRequest.to(networkParameters, keyTarget, amount);
			
			if (wallet.completeTx(sendRequest)) {
				
				wallet.saveToFile(walletFile);
				
				System.out.println("tx");
				System.out.println(sendRequest.tx.toString());
				
				// peerGroup.broadcastTransaction(sendRequest.tx);
			}
			else {
				
				System.out.println("no?");
			}
		} 
		catch (IOException e) {
			
			e.printStackTrace();
		} 
	}
}
