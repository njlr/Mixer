import math
import random

from itertools import product

from utils import next_power
from utils import data_oblivious_shuffle

from viff.util import find_prime

from test import test_shuffle



print 1 ^ 1 ^ 1
print 1 ^ 1 ^ 0
print 1 ^ 0 ^ 0

print reduce(lambda x, y: x ^ y, [1, 1, 0])



