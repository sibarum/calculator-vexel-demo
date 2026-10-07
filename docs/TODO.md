# TODO

Work on **Calculator** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] The entry is cleared by the Enter handler, which runs on a worker, so a line typed within a few
      milliseconds of Enter can join the one before it. Only a script types that fast, but a driver should
      `settle` after each Enter.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that
      file about which authored colours the construction can reproduce and which have to be declared.
- [ ] Decide what closing the window means. Closing currently closes, which is the right default; an
      application with unsaved state registers `shell.onClose` from a `@Provides` method in `Recipes` that takes
      the `Shell` — which puts it in the last phase, where a close gate can be registered — and answers the
      `CloseRequest` when it knows. One gate per application -- a second registration is refused, because the
      one it replaced is as likely as not the one that knew about the unsaved documents.

## Later

- [ ] Automation coverage: `-Dautomation=on` opens a driving socket, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
