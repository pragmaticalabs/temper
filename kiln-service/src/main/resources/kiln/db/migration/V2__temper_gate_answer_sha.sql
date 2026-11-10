-- The sha temper's gate answer named (WP6 fix round). head_sha is the head kiln asked about; answer_sha is what temper
-- answered for: the head itself, or a prefix of it of 7 to 40 lowercase hex characters. The view shows an answer only
-- when answer_sha is such a prefix of the PR's current head_sha, so an answer for another SHA is never shown as this
-- head's. Set exactly when answer is set.
-- NOT VALID: a row written before this column has an answer and no answer_sha; the view never shows it, and the next
-- temper cycle rewrites it.
ALTER TABLE temper_gate ADD COLUMN answer_sha text NULL;
ALTER TABLE temper_gate ADD CONSTRAINT temper_gate_answer_sha CHECK ((answer IS NULL) = (answer_sha IS NULL)) NOT VALID;
ALTER TABLE temper_gate ADD CONSTRAINT temper_gate_answer_sha_hex CHECK (answer_sha ~ '^[0-9a-f]{7,40}$') NOT VALID;
