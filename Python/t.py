import math
import random

from itertools import product

from utils import next_power
from utils import data_oblivious_shuffle

from viff.util import find_prime

from test import test_shuffle



test_shuffle(data_oblivious_shuffle, [1, 2, 3, 4, 5, 6], [1, 2, 3, 4, 5, 6], 99999999)



