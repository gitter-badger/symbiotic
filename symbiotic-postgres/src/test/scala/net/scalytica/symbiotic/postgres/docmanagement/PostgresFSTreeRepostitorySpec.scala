package net.scalytica.symbiotic.postgres.docmanagement

import net.scalytica.symbiotic.test.specs.{FSTreeRepositorySpec, PostgresSpec}

class PostgresFSTreeRepostitorySpec
    extends FSTreeRepositorySpec
    with PostgresSpec {

  override val folderRepo = new PostgresFolderRepository(config)
  override val fstreeRepo = new PostgresFSTreeRepository(config)

}
