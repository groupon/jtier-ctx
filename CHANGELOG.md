# 0.7.0

* **BREAKING** Change `close()` to be `cancel(null)` instead of detach.
* **BREAKING** A thread *always* has a `Ctx`, detach causes a new empty to be attached.
* Add `Ctx#cancel(Throwable)` to cancel with a cause
* Add `Ctx#detach` to replace `Ctx#close` for detaching a Ctx
* Add `Ctx.Key#get` and `Ctx.Key#set` convenience operations

# 0.3.0

* Starting a timeout now creates a new Ctx with the timeout, rather
  than putting the timeout on the current context.
