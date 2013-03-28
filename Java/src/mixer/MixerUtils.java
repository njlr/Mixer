package mixer;

import java.math.BigInteger;
import java.util.Collection;

import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletTransaction;
import com.google.bitcoin.core.WalletTransaction.Pool;

public strictfp final class MixerUtils {
	
	private MixerUtils() {
		
		super();
	}
	
	/**
	 * Checks that a collection contains uniform elements
	 * @param collection
	 * @return True if all the elements are the same. False otherwise. 
	 */
	public static <T> boolean isUniform(final Collection<T> collection) {
		
		boolean first = true;
		
		T j = null;
		
		for (final T i : collection) {
			
			if (first) {
				
				first = false;
				
				j = i;
			}
			else {
				
				if (i == null) {
					
					if (j != null) {
						
						return false;
					}
				}
				else {
					
					if (!i.equals(j)) {
						
						return false;
					}
				}
				
				j = i;
			}
		}
		
		return true;
	}
	
	/**
	 * Finds the most appropriate unspent output to use for a mix. 
	 * This is the output closest to, but no lower than, the target amount. 
	 * @param wallet The wallet to take the unspent output from
	 * @param amount The amount of the mix in BTC
	 * @return The most appropriate unspent output
	 */
	public static TransactionOutput getClosestOutput(final Wallet wallet, final BigInteger amount) {
		
		boolean first = true;
		
		TransactionOutput best = null;
		
		for (final WalletTransaction i : wallet.getWalletTransactions()) {
			
			if (i.getPool() == Pool.UNSPENT) {
				
				for (final TransactionOutput j : i.getTransaction().getOutputs()) {
					
					if (j.isMine(wallet) && j.isAvailableForSpending()) {
						
						if (j.getValue().compareTo(amount) >= 0) {
							
							if (first) {
								
								first = false;
								
								best = j;
							}
							else {
								
								if (j.getValue().compareTo(best.getValue()) < 0) {
									
									best = j;
								}
							}
						}
					}
				}
			}
		}
		
		return best;
	}
}
