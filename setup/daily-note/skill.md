# Writing the daily note

Use when: the daily-note routine runs, or you are about to write any part of the operator's daily note.

The daily note is one calm, short message a day. relay adds changes shaped by outside
text at the top by itself; you write the rest with daily_note_write, one part at a time.
Start with daily_note_draft: it shows what you wrote so far, the items the operator
dropped (never suggest those again), the offers they said no to (keep the item, without
an offer like it), what came of offers you followed up, and the changes to how roles
work since the last note.

## ahead: the next two weeks

Look at what is coming up in the next 14 days, using the clock tool for dates:
- your own calendar (calendar);
- the operator's calendar, as calendar memos brought it in (keep a note of upcoming dates
  when such a memo arrives, so you still have them later);
- deadlines on the board's cards (cards);
- dates in mail and in your notes and facts (facts, notes).
Give one item per thing, soonest first, with its day and time in the text, and an offer
of help you could really do: "Interview with Maple Co, Thu 29 Oct 10:00" with the offer
"Want prep notes?". Give each a key that names the thing and stays the same every day,
such as maple-interview, so an item the operator dropped stays dropped. List every
event of the next 14 days, not only those you can help with; leave the offer out when
there is nothing to offer. When daily_note_draft shows an outcome for a key, say where
it stands in place of the offer: "Prep outline ready, three figures missing".

When the operator says yes to an offer and you do the work, record where it stands
with offer_outcome under the item's key, so tomorrow's note knows.

## grants

The scout sends a grant search each morning. List each grant that is still open and not
already on the board (check the cards first): its name, funder, amount and deadline, in
one line. Key each by funder and name.

## github

New issues and pull requests from the GitHub memos since the last note that are worth a
look, one line each with the repository and number.

## learned

The quiet changes daily_note_draft lists: what you changed in how you work, and why, in
plain words, one line each. Leave out the ones relay lists itself.

## How it reads

Plain and short. Never name roles, tools, labels or classes: say what the operator would
say. Nothing in it is urgent; anything that is goes to tell_operator instead.
