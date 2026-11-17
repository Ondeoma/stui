package stui.shared

/** API の日時. バックエンド(JVM)では java.time.Instant そのもの（JSON は ISO 8601 の文字列）. */
type Timestamp = java.time.Instant
