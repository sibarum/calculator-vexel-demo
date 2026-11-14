# TODO

Work on **Calculator** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] The entry is cleared when `Evaluate`'s reply arrives, a tick or more after Enter, so anything typed in
      between is cleared with it. Only a script types that fast, but a driver should `settle` after each Enter.
- [ ] The plot explorer still resamples on an executor of its own, guarded by its own lock, and draws from that
      thread: the 0.1 shape. In 0.2 it would be a `@Job` taking the graph and the grid, with the explorer dropping
      stale replies by ticket. It needs `Graph` to pass as a deeply immutable payload first.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that
      file about which authored colours the construction can reproduce and which have to be declared.
- [ ] Decide what closing the window means. Closing currently closes, which is the right default; an
      application with unsaved state hears `CloseRequested` in a part and answers `CloseAnswer` when it knows
      (0.2 replaced 0.1's `onClose` callback with that pair). The settings window can be asked about the same way.

## Later

- [ ] Automation coverage: `-Dautomation=on` opens a driving socket, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
