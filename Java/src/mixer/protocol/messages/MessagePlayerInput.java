package mixer.protocol.messages;

import java.util.HashSet;
import java.util.Set;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.TransactionOutput;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public strictfp final class MessagePlayerInput extends Message {
	
	private static final long serialVersionUID = 7437481367004735495L;
	
	private final NetworkParameters networkParameters;
	private final TransactionOutput source;
	private final Set<byte[]> targetAddressHash160s;
	
	public TransactionOutput getSource() {
		
		return this.source;
	}
	
	public Set<byte[]> getTargetAddressHash160s() {
		
		return ImmutableSet.copyOf(this.targetAddressHash160s);
	}
	
	public Set<Address> getTargetAddresses() {
		
		final Set<Address> result = new HashSet<Address>();
		
		for (final byte[] i : this.targetAddressHash160s) {
			
			result.add(new Address(this.networkParameters, i));
		}
		
		return result;
	}
	
	public MessagePlayerInput(final TransactionOutput source, final Set<Address> targetAddresses) {
		
		super();
		
		Preconditions.checkArgument(source != null);
		
		Preconditions.checkArgument(targetAddresses != null);
		Preconditions.checkArgument(targetAddresses.size() > 0);
		
		NetworkParameters networkParameters = null;
		
		boolean first = true;
		
		for (final Address i : targetAddresses) {
			
			if (first) {
				
				first = false;
				
				networkParameters = i.getParameters();
			}
			else {
				
				Preconditions.checkArgument(networkParameters.equals(i.getParameters()), "Non-unform network! ");
			}
		}
		
		this.networkParameters = networkParameters;
		
		this.source = source;
		
		this.targetAddressHash160s = new HashSet<byte[]>();
		
		for (final Address i : targetAddresses) {
			
			this.targetAddressHash160s.add(i.getHash160());
		}
	}
	
	@Override
	public String toString() {
		
		return "MessagePlayerInput[source: " + this.source.toString() + ", targetAddresses: " + this.targetAddressHash160s.toString() + "]";
	}
}
