// Package badfixture has a clear, stable Go rule violation.
//
// equalOperands compares a value to itself: the two operands of == are
// identical, which the Go analyzer's Sonar way profile flags as go:S1764
// ("identical expressions should not be used on both sides of a binary
// operator").
package badfixture

// IsSelfEqual always returns true; the comparison is gratuitous.
func IsSelfEqual(value int) bool {
	return value == value
}
