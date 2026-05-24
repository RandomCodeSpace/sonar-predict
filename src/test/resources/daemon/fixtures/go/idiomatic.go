package badfixture

// ExportedFunction uses the PascalCase that Go mandates for exported
// identifiers; the S100 function-naming rule must accept it.
func ExportedFunction(value int) int {
	return value
}

// unexportedHelper uses the idiomatic mixedCaps of an unexported function.
func unexportedHelper() bool {
	return true
}
