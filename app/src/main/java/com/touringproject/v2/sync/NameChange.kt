package com.touringproject.v2.sync

/**
 * What stops the user name from changing now, in the order the rider is told (08a §6, US-E-06.008); null when nothing
 * does. The screen and the write inside RoomTakeover's transaction both ask here, so they never part.
 */
fun nameChangeBlock(rideActive: Boolean, unsent: Int, waitingToSave: Boolean): BlockReason? = when {
  rideActive -> BlockReason.RIDE
  unsent > 0 -> BlockReason.UNSENT
  waitingToSave -> BlockReason.WAITING
  else -> null
}
