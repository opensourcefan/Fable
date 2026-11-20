ALTER TABLE book ADD COLUMN staging_completed_at TIMESTAMP NULL;

UPDATE book
SET staging_completed_at = COALESCE(last_metadata_fetch_at, CURRENT_TIMESTAMP)
WHERE staged = TRUE
  AND (
    last_metadata_fetch_at IS NOT NULL
    OR EXISTS (
      SELECT 1
      FROM book_metadata m
      WHERE m.book_id = book.id
        AND m.isbn_verified = TRUE
    )
  );
