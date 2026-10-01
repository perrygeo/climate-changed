`climate-changed` is a Clojure and ClojureScript full-stack web application.

`infra/` is the NixOS and terraform configuration for production.

`src/` is the `.clj`, `.cljc`, `.cljs`, and `.rs` source files.

`Makefile` describes all high-level dev tasks.


## Evaluating code
The command `clojure-eval` is installed on your path.

Use the `clojure-eval` skill to test code, check if edited files compile, verify function behavior, eval code without editing files, or interact with a running REPL session state.
Summary (in case you can't load the skill):

1. Discover the nREPL with `clj-nrepl-eval --discover-ports`
2. Evaluate Clojure forms with `clj-nrepl-eval  -p <PORT> ...`
3. Optionally jack into the ClojureScript with `(shadow/repl :app)`
    a. Inspected the browser with `js/*` functions, evaluating in the live browser runtime
## Clojure Parenthesis Repair

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

The tool automatically formats files with cljfmt when it processes them.
