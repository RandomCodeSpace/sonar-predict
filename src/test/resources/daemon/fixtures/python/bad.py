"""Fixture with a clear Python rule violation.

Triggers python:S1481 - unused local variables should be removed.
"""


def compute(value):
    unused_total = value * 2
    return value + 1
