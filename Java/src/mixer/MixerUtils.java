package mixer;

import java.util.Collection;

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
}
