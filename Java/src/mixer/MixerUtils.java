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
	
	public static <T> boolean isUniform(Collection<T> collection) {
		
		boolean first = true;
		
		T j = null;
		
		for (T i : collection) {
			
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
