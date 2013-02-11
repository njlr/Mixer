package mixer.protocol.messages;

import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public strictfp final class MessagePlayerInput extends Message {
	
	private static final long serialVersionUID = 7437481367004735495L;
	
	private final String sourceAddress;
	private final Set<String> targetAddresses;
	
	public String getSourceAddress() {
		
		return this.sourceAddress;
	}
	
	public Set<String> getTargetAddresses() {
		
		return ImmutableSet.copyOf(this.targetAddresses);
	}
	
	public MessagePlayerInput(final String sourceAddress, final Set<String> targetAddresses) {
		
		super();
		
		Preconditions.checkArgument(sourceAddress != null);
		
		Preconditions.checkArgument(targetAddresses != null);
		Preconditions.checkArgument(targetAddresses.size() > 0);
		
		this.sourceAddress = sourceAddress;
		this.targetAddresses = ImmutableSet.copyOf(targetAddresses);
	}
}
