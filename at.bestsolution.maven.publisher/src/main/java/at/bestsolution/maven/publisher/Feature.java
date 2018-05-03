package at.bestsolution.maven.publisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class Feature {
	private String featureId;
	private String name;
	private String vendor;
	private String version;
	private List<RequireBundle> bundles = new ArrayList<>();
	private List<ResolvedBundle> resolvedBundleDeps;
	private List<Feature> features = new ArrayList<>();
	private List<Feature> resolvedFeatures;

	public List<RequireBundle> getBundles() {
		return bundles;
	}
	
	public List<Feature> getFeatures() {
		return features;
	}
	
	public List<ResolvedBundle> getResolvedBundleDeps() {
		return resolvedBundleDeps;
	}
	
	public List<Feature> getResolvedFeatures() {
		return resolvedFeatures;
	}
	
	public String getFeatureId() {
		return featureId;
	}

	public void setFeatureId(String featureId) {
		this.featureId = featureId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getVendor() {
		return vendor;
	}

	public void setVendor(String vendor) {
		this.vendor = vendor;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	@Override
	public String toString() {
		return "Feature [featureId=" + featureId + ", name=" + name + ", vendor=" + vendor + ", version=" + version
				+ "]";
	}

	public void resolveBundles(Function<Feature, Set<ResolvedBundle>> resolver) {
		if (resolvedBundleDeps == null) {
			resolvedBundleDeps = new ArrayList<ResolvedBundle>(resolver.apply(this));
			Collections.sort(resolvedBundleDeps, new Comparator<ResolvedBundle>() {
				@Override
				public int compare(ResolvedBundle o1, ResolvedBundle o2) {
					return o1.getBundle().getBundleId().compareTo(o2.getBundle().getBundleId());
				}
			});
		}
	}
	
	public void resolveFeatures(Function<Feature, Set<Feature>> resolver) {
		if( resolvedFeatures == null ) {
			resolvedFeatures = new ArrayList<>(resolver.apply(this));
			Collections.sort(resolvedFeatures, new Comparator<Feature>() {
				@Override
				public int compare(Feature o1, Feature o2) {
					return o1.getFeatureId().compareTo(o2.getFeatureId());
				}
			});
		}
	}
}
