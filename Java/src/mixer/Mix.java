package mixer;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

public strictfp final class Mix {
	
	private final Set<String> oldAddresses;
	private final Set<String> freshAddresses;
	
	public Set<String> getOldAddresses() {
		
		return ImmutableSet.copyOf(this.oldAddresses);
	}
	
	public Set<String> getFreshAddresses() {
		
		return ImmutableSet.copyOf(this.freshAddresses);
	}

	public Mix(Set<String> oldAddresses, Set<String> freshAddresses) {
		
		super();
		
		this.oldAddresses = ImmutableSet.copyOf(oldAddresses);
		this.freshAddresses = ImmutableSet.copyOf(freshAddresses);
	}
}
