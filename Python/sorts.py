def pair_sort(a, b):
    
    c = a > b
    
    x = c * a + (1 - c) * b
    y = c * b + (1 - c) * a
    
    return x, y


def bubble_sort(xs): 

    n = len(xs) - 1
    
    while n >= 0: 
        
        for j in range(0, n): 
            
            g, l = pair_sort(xs[j], xs[j + 1])
            
            xs[j] = l
            xs[j + 1] = g
        
        n -= 1


def shell_gaps(n, i): 
    
    return n / (i + 1)


def shell_sort(xs, gap_func=shell_gaps, sort_algo=bubble_sort):
    
    n = len(xs)
    
    # gap index
    g = 0
    
    # Shell's (original) gap sequence: n/2, n/4, ..., 1
    gap = gap_func(n, g)
    
    # loop over the gaps
    while gap > 0:
        
        # gather the elements to sort
        ys = xs[0:n:gap]
        
        # sort them
        sort_algo(ys)
        
        # put them back
        i = 0
        
        for y in ys: 
            
            xs[i * gap] = y
            
            i += 1
        
        # update the gap
        g += 1
        
        gap = gap_func(n, g)


