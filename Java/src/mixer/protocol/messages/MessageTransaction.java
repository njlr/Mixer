package mixer.protocol.messages;

import com.google.bitcoin.core.Transaction;

public strictfp final class MessageTransaction extends Message {
	
	private static final long serialVersionUID = -4082383747055263150L;
	
	private final Transaction transaction;
	
	public Transaction getTransaction() {
		
		return this.transaction;
	}
	
	public MessageTransaction(final Transaction transaction) {
		
		super();
		
		this.transaction = transaction;
	}
	
	@Override
	public String toString() {
		
		return "MessageTransaction[transaction: " + this.transaction.toString() + "]";
	}
}
