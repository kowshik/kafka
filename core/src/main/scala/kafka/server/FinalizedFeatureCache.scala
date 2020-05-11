package kafka.server

import kafka.utils.Logging
import org.apache.kafka.common.feature.{Features, VersionLevelRange}

// Raised whenever there was an error in updating the FinalizedFeatureCache with features.
class FeatureCacheUpdateException(message: String) extends RuntimeException(message) {
}

// Helper class that represents finalized features along with an epoch value.
case class FinalizedFeaturesAndEpoch(features: Features[VersionLevelRange], epoch: Int) {

  def isValid(newEpoch: Int): Boolean = {
    newEpoch >= epoch
  }

  override def toString(): String = {
    "FinalizedFeaturesAndEpoch(features=%s, epoch=%s)".format(features, epoch)
  }
}

/**
 * A mutable cache containing the latest finalized features and epoch. This cache is populated by a
 * FinalizedFeatureChangeListener.
 *
 * Currently the main reader of this cache is the read path that serves an ApiVersionsRequest
 * returning the features information in the response. In the future, as the feature versioning
 * system in KIP-584 is used more widely, this cache could be read by other read paths trying to
 * learn the finalized feature information.
 */
object FinalizedFeatureCache extends Logging {
  @volatile private var featuresAndEpoch: Option[FinalizedFeaturesAndEpoch] = Option.empty

  /**
   * @return   the latest known FinalizedFeaturesAndEpoch. If the returned value is empty, it means
   *           no FinalizedFeaturesAndEpoch exists in the cache at the time when this
   *           method is invoked. This result could change in the future whenever the
   *           updateOrThrow method is invoked.
   */
  def get: Option[FinalizedFeaturesAndEpoch] = {
    featuresAndEpoch
  }

  def empty: Boolean = {
    featuresAndEpoch.isEmpty
  }

  /**
   * Clears all existing finalized features and epoch from the cache.
   */
  def clear(): Unit = {
    featuresAndEpoch = Option.empty
    info("Cleared cache")
  }

  /**
   * Updates the cache to the latestFeatures, and updates the existing epoch to latestEpoch.
   * Raises an exception when the operation is not successful.
   *
   * @param latestFeatures   the latest finalized features to be set in the cache
   * @param latestEpoch      the latest epoch value to be set in the cache
   *
   * @throws                 FeatureCacheUpdateException if the cache update operation fails
   *                         due to invalid parameters or incompatibilities with the broker's
   *                         supported features. In such a case, the existing cache contents are
   *                         not modified.
   */
  def updateOrThrow(latestFeatures: Features[VersionLevelRange], latestEpoch: Int): Unit = {
    updateOrThrow(FinalizedFeaturesAndEpoch(latestFeatures, latestEpoch))
  }

  private def updateOrThrow(latest: FinalizedFeaturesAndEpoch): Unit = {
    val existingStr = featuresAndEpoch.map(existing => existing.toString).getOrElse("<empty>")
    if (!featuresAndEpoch.isEmpty && featuresAndEpoch.get.epoch > latest.epoch) {
      val errorMsg = ("FinalizedFeatureCache update failed due to invalid epoch in new finalized %s." +
        " The existing finalized is %s").format(latest, existingStr)
      throw new FeatureCacheUpdateException(errorMsg)
    } else {
      val incompatibleFeatures = SupportedFeatures.incompatibleFeatures(latest.features)
      if (incompatibleFeatures.nonEmpty) {
        val errorMsg = ("FinalizedFeatureCache updated failed since feature compatibility" +
          " checks failed! Supported %s has incompatibilities with the latest finalized %s." +
          " The incompatible features are: %s.").format(
          SupportedFeatures.get, latest, incompatibleFeatures)
        throw new FeatureCacheUpdateException(errorMsg)
      }
    }
    val logMsg = "Updated cache from existing finalized %s to latest finalized %s".format(
      existingStr, latest)
    featuresAndEpoch = Some(latest)
    info(logMsg)
  }
}
