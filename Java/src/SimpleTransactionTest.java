import java.io.File;
import java.math.BigInteger;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletTransaction;
import com.google.bitcoin.core.WalletTransaction.Pool;

public strictfp final class SimpleTransactionTest {
	
	private SimpleTransactionTest() {
		
		super();
	}
	
	public static void main(String[] args) throws Exception {
		
		// Load our test wallet
		final File walletFile = new File("./lib/test.wallet");
		
		final Wallet wallet = Wallet.loadFromFile(walletFile);
		
		// Store its network type
		final NetworkParameters networkParameters = wallet.getNetworkParameters();
		
		// Create a new tx
		final Transaction tx = new Transaction(networkParameters);
		
		// Create a recipient
		final ECKey recipient = new ECKey(new BigInteger("10001110101")); // Just a test so deterministic key selection is fine. 
		
		// Look for an unspent output... 
		boolean foundInput = false;
		
		for (WalletTransaction i : wallet.getWalletTransactions()) {
			
			if (i.getPool() == Pool.UNSPENT) {
				
				for (TransactionOutput j : i.getTransaction().getOutputs()) {
					
					if (j.isMine(wallet) && j.isAvailableForSpending() && j.getValue().compareTo(BigInteger.ZERO) > 0 && j.getValue().compareTo(Utils.toNanoCoins(10, 0)) < 0) {
						
						// Send all of it to the recipient
						tx.addInput(j);
						tx.addOutput(j.getValue(), recipient.toAddress(networkParameters));
						
						// Stop looking
						foundInput = true;
						
						break;
					}
				}
			}
			
			if (foundInput) {
				
				break;
			}
		}
		
		if (foundInput) {
			
			// Sign the tx
			tx.signInputs(SigHash.ALL, wallet);
			
			// Commit
			//wallet.commitTx(tx);
			//wallet.saveToFile(walletFile);
			
			// Show the tx to the user
			System.out.println(tx);
		}
		else {
			
			System.out.println("No viable input found. ");
		}
	}
}