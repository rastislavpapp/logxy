/**
 * The incubating Vector API module, which the newline search uses (docs/adr/0002). The JVM resolves an incubator
 * module only when asked with `--add-modules`, even when its runtime holds it; without it the search falls back to SWAR.
 */
const val VECTOR_MODULE = "jdk.incubator.vector"
