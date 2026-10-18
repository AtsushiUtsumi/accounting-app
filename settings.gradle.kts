rootProject.name = "accounting-app"

// ドメインはそのまま別プロジェクトとして残し、composite build で取り込む(CLAUDE.md §2)。
// ドメインが Spring に依存しないことを、ビルドの構成そのもので保証する。
includeBuild("../accounting-domain")
