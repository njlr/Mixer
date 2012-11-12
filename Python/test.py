def test_shuffle(in_place_shuffling_func, xs, perm, n): 
    
    assert len(xs) == len(perm)
    
    counters = [0] * len(xs)
    
    for i in range(n): 
        
        ys = list(xs)

        in_place_shuffling_func(ys)
        
        for index, item in enumerate(ys): 
            
            if item == perm[index]: 
                
                counters[index] += 1
    
    for i in counters: 

        print float(i) / n



