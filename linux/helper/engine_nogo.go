//go:build !amneziawg_go

package main

import "errors"

// The default build: awgcore only, no amneziawg-go inside (engine_go.go has it).
const builtinGo = false

func startGo(int) (engine, error) { return nil, errors.New("this helper has no amneziawg-go") }
