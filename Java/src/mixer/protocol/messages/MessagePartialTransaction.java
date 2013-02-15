package mixer.protocol.messages;

import com.google.bitcoin.core.Transaction;
import com.google.common.base.Preconditions;

public strictfp final class MessagePartialTransaction extends Message {
	
	private static final long serialVersionUID = 9211193151437744281L;
	
	private final Transaction transaction;
	
	private final int indexToSign;
	
	public Transaction getTransaction() {
		
		return this.transaction;
	}
	
	public int getIndexToSign() {
		
		return this.indexToSign;
	}
	
	public MessagePartialTransaction(final Transaction transaction, final int indexToSign) {
		
		super();
		
		Preconditions.checkArgument(transaction != null);
		Preconditions.checkArgument(indexToSign >= 0);
		Preconditions.checkArgument(indexToSign < transaction.getInputs().size());
		
		this.transaction = transaction;
		
		this.indexToSign = indexToSign;
	}
	
	@Override
	public String toString() {
		
		return "MessagePartialTransaction[transaction: " + this.transaction.toString() + ", indexToSign: " + this.indexToSign + "]";
	}
}
