package com.touringproject.v2.store

import com.touringproject.v2.sync.OwnerStore

/** The owner of the records, kept in Room beside them (adr/0015). */
class RoomOwner(private val dao: TakeoverDao) : OwnerStore {
  override suspend fun owner(): String? = dao.owner()

  override suspend fun adopt(userName: String) = dao.setOwner(OwnerEntity(userName = userName))
}
