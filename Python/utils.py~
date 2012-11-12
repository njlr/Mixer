import random



def next_power(n, p): 
    
    looking = True
    
    x = p
    
    while looking:
        
        if x > n: 
            
            looking = False
        
        else: 
            
            x *= x
    
    return x


def random_swap(a, b): 
    
    r = random.choice([0, 1])
        
    x = a * r + b * (1 - r)
    y = b * r + a * (1 - r)
    
    return x, y


def data_oblivious_shuffle(xs, random_swap_function = random_swap): 
    
    for i in range(len(xs)): 
        
        for j in range(i) + range(i + 1, len(xs)): 
        
            x, y = random_swap_function(xs[i], xs[j])
            
            xs[i] = x
            xs[j] = y


