package obsidiananki.model

/** HOW TO FIND A NOTE'S CARDS IN ANKI, WRITTEN DOWN ONCE.
  *
  * WHY THIS EXISTS, AND IT IS NOT A TIDINESS ARGUMENT. Until 2026-08-29 this knowledge lived in
  * an Obsidian shell command in Marc's own configuration — a `curl` one-liner that built
  * `tag:src::<id>::*` by hand. The README presented that as the feature's whole trick: the search
  * is composable without asking this tool anything, so the binding costs nothing.
  *
  * WHAT THAT ACTUALLY BOUGHT was a copy of the identity format in a file this repository cannot
  * read, cannot test and cannot migrate. Moving the identity from a tag into a field would have
  * broken that keystroke silently — an EMPTY Browse window, which reads as *this note made no
  * cards* rather than as a fault. Marc found it by asking; nothing here could have.
  *
  * SO THE RULE IS THE ONE THE ADD-ON ALREADY STATES ABOUT DECODING: the tool that writes the
  * identity is the tool that reads it. A caller supplies a note's frontmatter id and gets back a
  * search. It never learns the prefix, the separator, or which of the two homes an identity is
  * currently kept in — so all three may change without a single consumer being edited.
  */
object CardSearch:

  /** EVERY NOTE THIS TOOL OWNS, in whichever home its identity currently sits.
    *
    * BOTH, AND THE `or` IS NOT OPTIONAL — NOR IS IT TRANSITIONAL. The argument is written out
    * once, at `plan/Executor.scala`'s resolution comment: a note on a note type this tool does
    * not own has nowhere to put the field, so for that note the tag is the only possible home,
    * permanently. Two kinds of note keeping the same value in the only place each can.
    *
    * WHAT A SEARCH NAMING ONE HOME WOULD COST. It finds half a collection, and the half it
    * misses looks to this tool like notes that DO NOT EXIST — which is the input that makes it
    * create them again. Duplicating a collection is the worst outcome available here.
    */
  def everythingOwned: String =
    s"\"${Marker.IdentityField}:${OwnedTag.SrcPrefix}::*\" or \"tag:${OwnedTag.SrcPrefix}::*\""

  /** Every card this tool made from one note, as an Anki search.
    *
    * BOTH HOMES, AND NEITHER HALF MAY BE REMOVED. An earlier version of this docstring said the
    * tag half "goes when the tag does" — WITHDRAWN 2026-08-29, because the tag does not go. A
    * note on a note type this tool does not own cannot hold the field and keeps its identity in
    * a tag permanently; `plan/Executor.scala`'s resolution comment carries the full argument.
    *
    * DELETING THE TAG HALF WOULD BE INVISIBLE, which is why the withdrawn sentence was worth
    * chasing down rather than leaving to be noticed later. Such a note's cards would simply stop
    * appearing, and an empty Browse window reads as *this note made no cards* rather than as a
    * fault — the exact failure this file was created to close.
    *
    * ANKI'S FIELD SEARCH TOLERATES `::` IN A VALUE — verified 2026-08-29 against Marc's live
    * collection, read-only, using cloze text which already contains `{{c1::…}}`: `Text:*c1::*`
    * matched exactly the notes `note:"Obsidian Cloze"` matched. Only the FIRST colon separates
    * the field name from the value, so the rest need no escaping.
    *
    * QUOTED, BECAUSE A SEARCH TERM CONTAINING `:` MUST BE. Anki reads an unquoted `a:b:c` as a
    * field query it cannot resolve; the quotes make the whole term one value.
    *
    * SUSPENDED CARDS ARE INCLUDED HERE, deliberately, and that is a difference from the drill
    * search. A card retired by a reworded heading keeps its identity beside its `orphaned::`
    * flag, so Browse shows the live card and the retired one side by side — which is the
    * clearest available view of what a rewording cost. A drill wants the opposite.
    */
  def forNoteId(frontmatterId: String): String =
    val id = frontmatterId.trim
    if id.isEmpty then
      // NEVER A BARE WILDCARD. `tag:src::*` matches every card this tool has ever made, so an
      // empty id must yield a search matching NOTHING rather than the whole collection — the
      // same reasoning the add-on's drill search records for its own empty case.
      "nid:0"
    else
      val encoded = TagCodec.encodeComponent(id.toLowerCase(java.util.Locale.ROOT))
      s"\"${Marker.IdentityField}:${OwnedTag.SrcPrefix}::$encoded::*\" or " +
        s"\"tag:${OwnedTag.SrcPrefix}::$encoded::*\""
