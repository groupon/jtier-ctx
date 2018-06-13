# 0.7.0

* **BREAKING** Change `close()` to be `cancel(null)` instead of detach.

# 0.3.0

* Starting a timeout now creates a new Ctx with the timeout, rather
  than putting the timeout on the current context.
