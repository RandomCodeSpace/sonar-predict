// Fixture with a clear JavaScript rule violation.
// Triggers javascript:S1481 - unused local variables should be removed.

function compute(value) {
  var unusedTotal = value * 2;
  return value + 1;
}

module.exports = compute;
