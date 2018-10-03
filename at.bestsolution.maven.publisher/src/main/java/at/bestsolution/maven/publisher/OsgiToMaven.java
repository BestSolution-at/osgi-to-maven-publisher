/*******************************************************************************
 * Copyright (C) 2017  BestSolution.at
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 *******************************************************************************/
package at.bestsolution.maven.publisher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.maven.index.Field;
import org.apache.maven.index.FlatSearchRequest;
import org.apache.maven.index.FlatSearchResponse;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.context.ExistingLuceneIndexMismatchException;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.expr.SourcedSearchExpression;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.wagon.Wagon;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

import com.google.common.io.Files;

/**
 * Base class publishing artifacts
 */
public abstract class OsgiToMaven {
	protected final File workingDirectory = Files.createTempDir();
	private Map<String, Set<Bundle>> bundleExports;
	private Map<String, List<Bundle>> bundleById;
	private Function<Bundle, Optional<MavenDep>> mavenReplacementLookup = b -> Optional.empty();
	private Predicate<Bundle> publishFilter = b -> true;
	private Predicate<Feature> featurePublishFilter = f -> true;
	private Predicate<Bundle> bundleFilter = b -> true;
	private Predicate<Feature> featureFilter = b -> true;
	private Predicate<Bundle> snapshotFilter = b -> false;
	private Predicate<Feature> featureSnapshotFilter = b -> false;
	private Predicate<Bundle> sourceEnforced = b -> true;
	private Function<Bundle, String> projectUrlResolver = b -> "https://projects.eclipse.org/";
	private Function<Feature, String> featureProjectUrlResolver = b -> "https://projects.eclipse.org/";
	private Function<Bundle, String> groupIdResolver = b -> "osgi.to.maven";
	private Function<Feature, String> featureGroupIdResolver = b -> "osgi.to.maven";
	private Function<Bundle, SCM> scmUrlResolver = b -> new SCM("http://git.eclipse.org/c/", null, null);
	private Function<Feature, SCM> featureScmUrlResolver = b -> new SCM("http://git.eclipse.org/c/", null, null);
	private Function<Bundle, List<Developer>> developerResolver = b -> {
		if (b.getBundleId().startsWith("org.eclipse")) {
			return Collections.singletonList(new Developer("https://projects.eclipse.org/", null, null));
		} else {
			return Collections.emptyList();
		}
	};
	private Function<Feature, List<Developer>> featureDeveloperResolver = b -> {
		if (b.getFeatureId().startsWith("org.eclipse")) {
			return Collections.singletonList(new Developer("https://projects.eclipse.org/", null, null));
		} else {
			return Collections.emptyList();
		}
	};
	private Function<Bundle, List<License>> licenseResolver = b -> {
		if (b.getBundleId().startsWith("org.eclipse")) {
			return Collections.singletonList(
					new License("Eclipse Public License 1.0", "http://www.eclipse.org/legal/epl-v10.html"));
		} else if (b.getBundleId().startsWith("org.apache")) {
			return Collections.singletonList(
					new License("Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"));
		} else {
			return Collections.emptyList();
		}
	};
	private Function<Feature, List<License>> featureLicenseResolver = b -> {
		if (b.getFeatureId().startsWith("org.eclipse")) {
			return Collections.singletonList(
					new License("Eclipse Public License 1.0", "http://www.eclipse.org/legal/epl-v10.html"));
		} else if (b.getFeatureId().startsWith("org.apache")) {
			return Collections.singletonList(
					new License("Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"));
		} else {
			return Collections.emptyList();
		}
	};
	private BiPredicate<Bundle, ResolvedBundle> resolvedBundleFilter = (b, rb) -> true;
	private final String repositoryId;
	private final String repositoryUrl;
	private static final int PUBLISHING_THREADS = 1;
	private Indexer indexer;
	private IndexingContext indexContext;
	private Map<Object, List<Feature>> featureById;
	private static AtomicInteger counter = new AtomicInteger();

	private boolean dryRun = false;
	private boolean keepGenFolder = dryRun;

	public void setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
		this.keepGenFolder = dryRun;
	}
	
	public void setMavenReplacementLookup(Function<Bundle, Optional<MavenDep>> mavenReplacementLookup) {
		this.mavenReplacementLookup = mavenReplacementLookup;
	}

	public void setPublishFilter(Predicate<Bundle> publishFilter) {
		this.publishFilter = publishFilter;
	}
	
	public void setFeaturePublishFilter(Predicate<Feature> featurePublishFilter) {
		this.featurePublishFilter = featurePublishFilter;
	}

	public void setBundleFilter(Predicate<Bundle> bundleFilter) {
		this.bundleFilter = bundleFilter;
	}

	public void setProjectUrlResolver(Function<Bundle, String> projectUrlResolver) {
		this.projectUrlResolver = projectUrlResolver;
	}

	public void setSourceEnforced(Predicate<Bundle> sourceEnforced) {
		this.sourceEnforced = sourceEnforced;
	}

	public void setGroupIdResolver(Function<Bundle, String> groupIdResolver) {
		this.groupIdResolver = groupIdResolver;
	}

	public void setFeatureGroupIdResolver(Function<Feature, String> featureGroupIdResolver) {
		this.featureGroupIdResolver = featureGroupIdResolver;
	}

	public void setSnapshotFilter(Predicate<Bundle> snapshotFilter) {
		this.snapshotFilter = snapshotFilter;
	}
	
	public void setFeatureSnapshotFilter(Predicate<Feature> featureSnapshotFilter) {
		this.featureSnapshotFilter = featureSnapshotFilter;
	}

	public void setResolvedBundleFilter(BiPredicate<Bundle, ResolvedBundle> resolvedBundleFilter) {
		this.resolvedBundleFilter = resolvedBundleFilter;
	}

	public static class MavenDep {
		public final String groupId;
		public final String artifactId;

		public MavenDep(String groupId, String artifactId) {
			this.groupId = groupId;
			this.artifactId = artifactId;
		}
	}

	public static class Developer {
		public final Optional<String> url;
		public final Optional<String> name;
		public final Optional<String> organization;

		public Developer(String url, String name, String organization) {
			this.url = Optional.ofNullable(url);
			this.name = Optional.ofNullable(name);
			this.organization = Optional.ofNullable(organization);
		}
	}

	public static class SCM {
		public final String url;
		public final Optional<String> tag;
		public final Optional<String> connection;

		public SCM(String url, String tag, String connection) {
			this.url = url;
			this.tag = Optional.ofNullable(tag);
			this.connection = Optional.ofNullable(connection);
		}
	}

	public static class License {
		public final String name;
		public final String url;

		public License(String name, String url) {
			this.name = name;
			this.url = url;
		}
	}

	public OsgiToMaven(String repositoryUrl, String repositoryId) {
		this.repositoryUrl = repositoryUrl;
		this.repositoryId = repositoryId;
	}

	public static void unzipRepository(File archive, File outDir) throws ZipException, IOException {
		ZipFile zipfile = new ZipFile(archive);
		for (Enumeration<? extends ZipEntry> e = zipfile.entries(); e.hasMoreElements();) {
			ZipEntry entry = (ZipEntry) e.nextElement();
			unzipEntry(zipfile, entry, outDir);
		}
	}

	private static void unzipEntry(ZipFile zipfile, ZipEntry entry, File outputDir) throws IOException {
		if (entry.isDirectory()) {
			createDir(new File(outputDir, entry.getName()));
			return;
		}

		File outputFile = new File(outputDir, entry.getName());
		if (!outputFile.getParentFile().exists()) {
			createDir(outputFile.getParentFile());
		}

		BufferedInputStream inputStream = new BufferedInputStream(zipfile.getInputStream(entry));
		BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile));

		try {
			IOUtils.copy(inputStream, outputStream);
		} finally {
			outputStream.close();
			inputStream.close();
		}
	}

	private static void createDir(File dir) {
		dir.mkdirs();
	}

	private Set<ResolvedBundle> resolveFeatureBundles(Feature f) {
		Set<ResolvedBundle> rv = new HashSet<ResolvedBundle>();
		rv.addAll(f.getBundles().stream().filter(i -> !i.isOptional()).map(i -> {
			List<Bundle> list = bundleById.get(i.getName());
			if (list == null) {
				list = new ArrayList<>();
				if (!i.isOptional() && !i.getName().equals("system.bundle") && !i.getName().startsWith("javax")) {
					System.err
							.println("Could not resolve bundle '" + i.getName() + "' for '" + f.getFeatureId() + "' ");
				}
			}
			return list.stream().map(bb -> new ResolvedBundle(bb, false)).collect(Collectors.toSet());
		}).flatMap(bs -> bs.stream()).collect(Collectors.toSet()));
		return rv;
	}

	private Set<Feature> resolveFeatureFeatures(Feature f) {
		Set<Feature> rv = new HashSet<>();
		rv.addAll(f.getFeatures().stream().map(i -> featureById.get(i.getFeatureId())).flatMap(fs -> fs.stream())
				.collect(Collectors.toSet()));
		return rv;
	}

	private Set<ResolvedBundle> resolve(Bundle b) {
		Set<ResolvedBundle> rv = new HashSet<ResolvedBundle>();
		rv.addAll(b.getImportPackages().stream().filter(i -> !i.isOptional()).map(i -> {
			Set<Bundle> set = bundleExports.get(i.getName());
			if (set == null) {
				set = new HashSet<>();
				if (!i.isOptional() && !i.getName().startsWith("javax") && !i.getName().equals("org.ietf.jgss")) {
					System.err
							.println("Could not resolve package '" + i.getName() + "' for '" + b.getBundleId() + "' ");
				}
			}
			return set.stream().map(bb -> new ResolvedBundle(bb, false)).collect(Collectors.toSet());
		}).flatMap(bs -> bs.stream()).collect(Collectors.toSet()));

		rv.addAll(b.getRequiredBundles().stream().filter(i -> !i.isOptional()).map(i -> {
			List<Bundle> list = bundleById.get(i.getName());
			if (list == null) {
				list = new ArrayList<>();
				if (!i.isOptional() && !i.getName().equals("system.bundle") && !i.getName().startsWith("javax")) {
					System.err.println("Could not resolve bundle '" + i.getName() + "' for '" + b.getBundleId() + "' ");
				}
			}
			return list.stream().map(bb -> new ResolvedBundle(bb, false)).collect(Collectors.toSet());
		}).flatMap(bs -> bs.stream()).collect(Collectors.toSet()));

		rv.addAll(b.getImportPackages().stream().filter(i -> i.isOptional()).map(i -> {
			Set<Bundle> set = bundleExports.get(i.getName());
			if (set == null) {
				set = new HashSet<>();
				if (!i.isOptional() && !i.getName().startsWith("javax") && !i.getName().equals("org.ietf.jgss")) {
					System.err
							.println("Could not resolve package '" + i.getName() + "' for '" + b.getBundleId() + "' ");
				}
			}
			return set.stream().map(bb -> new ResolvedBundle(bb, true)).collect(Collectors.toSet());
		}).flatMap(bs -> bs.stream()).collect(Collectors.toSet()));

		rv.addAll(b.getRequiredBundles().stream().filter(i -> i.isOptional()).map(i -> {
			List<Bundle> list = bundleById.get(i.getName());
			if (list == null) {
				list = new ArrayList<>();
				if (!i.isOptional() && !i.getName().equals("system.bundle") && !i.getName().startsWith("javax")) {
					System.err.println("Could not resolve bundle '" + i.getName() + "' for '" + b.getBundleId() + "' ");
				}
			}
			return list.stream().map(bb -> new ResolvedBundle(bb, true)).collect(Collectors.toSet());
		}).flatMap(bs -> bs.stream()).collect(Collectors.toSet()));

		return rv.stream().filter(rb -> resolvedBundleFilter.test(b, rb)).collect(Collectors.toSet());
	}

	static String toPomVersion(String version, boolean withSnapshot) {
		if( withSnapshot ) {
			String[] parts = version.split("\\.");
			return parts[0] + "." + parts[1] + "." + parts[2] + "-SNAPSHOT";			
		}
		return version;
	}

	private void writeLine(OutputStreamWriter w, String v) {
		try {
			w.write(v + "\n");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void createPom(Bundle b) {
		try (FileOutputStream out = new FileOutputStream(
				new File(new File(workingDirectory, "poms"), b.getBundleId() + ".xml"));
				OutputStreamWriter w = new OutputStreamWriter(out)) {
			writeLine(w,
					"<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
			writeLine(w,
					"	xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">");
			writeLine(w, "	<modelVersion>4.0.0</modelVersion>");
			writeLine(w, "	<groupId>" + groupIdResolver.apply(b) + "</groupId>");
			writeLine(w, "	<artifactId>" + b.getBundleId() + "</artifactId>");
			writeLine(w, "	<version>" + toPomVersion(b.getVersion(), snapshotFilter.test(b)) + "</version>");

			// Meta-Data
			writeLine(w, "	<name>" + b.getName() + "</name>");
			writeLine(w, "	<description>" + b.getName() + "</description>");
			writeLine(w, "	<url>" + projectUrlResolver.apply(b) + "</url>");
			writeLine(w, "	<licenses>");
			licenseResolver.apply(b).forEach(l -> {
				writeLine(w, "		<license>");
				writeLine(w, "			<name>" + l.name + "</name>");
				writeLine(w, "			<url>" + l.url + "</url>");
				writeLine(w, "		</license>");
			});

			writeLine(w, "	</licenses>");
			writeLine(w, "	<scm>");
			writeLine(w, "		<url>" + scmUrlResolver.apply(b).url + "</url>");
			scmUrlResolver.apply(b).tag.ifPresent(v -> writeLine(w, "		<tag>" + v + "</tag>"));
			scmUrlResolver.apply(b).connection
					.ifPresent(v -> writeLine(w, "		<connection>" + v + "</connection>"));
			writeLine(w, "	</scm>");
			writeLine(w, "	<developers>");
			developerResolver.apply(b).forEach(d -> {
				writeLine(w, "		<developer>");
				d.url.ifPresent(v -> writeLine(w, "			<url>" + v + "</url>"));
				d.name.ifPresent(v -> writeLine(w, "			<name>" + v + "</name>"));
				d.organization.ifPresent(v -> writeLine(w, "			<organization>" + v + "</organization>"));
				// writeLine(w," <roles>");
				// writeLine(w," <role></role>");
				// writeLine(w," </roles>");
				writeLine(w, "		</developer>");
			});

			writeLine(w, "	</developers>");

			if (!b.getResolvedBundleDeps().isEmpty()) {
				w.write("	<dependencies>\n");
				for (ResolvedBundle rd : b.getResolvedBundleDeps()) {
					MavenDep dep = mavenReplacementLookup.apply(rd.getBundle())
							.orElse(new MavenDep(groupIdResolver.apply(rd.getBundle()), rd.getBundle().getBundleId()));
					w.write("		<dependency>\n");
					w.write("			<groupId>" + dep.groupId + "</groupId>\n");
					w.write("			<artifactId>" + dep.artifactId + "</artifactId>\n");
					w.write("			<version>" + toPomVersion(rd.getBundle().getVersion(), snapshotFilter.test(rd.getBundle())) + "</version>\n");
					if (rd.isOptional()) {
						w.write("			<optional>true</optional>\n");
					}
					w.write("		</dependency>\n");
				}
				w.write("	</dependencies>\n");
			}
			w.write("</project>");
			w.close();
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private void createPom(Feature f) {
		try (FileOutputStream out = new FileOutputStream(
				new File(new File(workingDirectory, "poms"), "feature_" + f.getFeatureId() + ".xml"));
				OutputStreamWriter w = new OutputStreamWriter(out)) {
			writeLine(w,
					"<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
			writeLine(w,
					"	xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">");
			writeLine(w, "	<modelVersion>4.0.0</modelVersion>");
			writeLine(w, "	<groupId>" + featureGroupIdResolver.apply(f) + "</groupId>");
			writeLine(w, "	<artifactId>" + f.getFeatureId() + "</artifactId>");
			writeLine(w, "	<version>" + toPomVersion(f.getVersion(), featureSnapshotFilter.test(f)) + "</version>");

			// Meta-Data
			writeLine(w, "	<name>" + f.getName() + "</name>");
			writeLine(w, "	<description>" + f.getName() + "</description>");
			writeLine(w, "	<url>" + featureProjectUrlResolver.apply(f) + "</url>");
			writeLine(w, "	<licenses>");
			featureLicenseResolver.apply(f).forEach(l -> {
				writeLine(w, "		<license>");
				writeLine(w, "			<name>" + l.name + "</name>");
				writeLine(w, "			<url>" + l.url + "</url>");
				writeLine(w, "		</license>");
			});

			writeLine(w, "	</licenses>");
			writeLine(w, "	<scm>");
			writeLine(w, "		<url>" + featureScmUrlResolver.apply(f).url + "</url>");
			featureScmUrlResolver.apply(f).tag.ifPresent(v -> writeLine(w, "		<tag>" + v + "</tag>"));
			featureScmUrlResolver.apply(f).connection
					.ifPresent(v -> writeLine(w, "		<connection>" + v + "</connection>"));
			writeLine(w, "	</scm>");
			writeLine(w, "	<developers>");
			featureDeveloperResolver.apply(f).forEach(d -> {
				writeLine(w, "		<developer>");
				d.url.ifPresent(v -> writeLine(w, "			<url>" + v + "</url>"));
				d.name.ifPresent(v -> writeLine(w, "			<name>" + v + "</name>"));
				d.organization.ifPresent(v -> writeLine(w, "			<organization>" + v + "</organization>"));
				// writeLine(w," <roles>");
				// writeLine(w," <role></role>");
				// writeLine(w," </roles>");
				writeLine(w, "		</developer>");
			});

			writeLine(w, "	</developers>");

			if (!f.getResolvedBundleDeps().isEmpty() || !f.getResolvedFeatures().isEmpty()) {
				w.write("	<dependencies>\n");
				for (ResolvedBundle rd : f.getResolvedBundleDeps()) {
					MavenDep dep = mavenReplacementLookup.apply(rd.getBundle())
							.orElse(new MavenDep(groupIdResolver.apply(rd.getBundle()), rd.getBundle().getBundleId()));
					w.write("		<dependency>\n");
					w.write("			<groupId>" + dep.groupId + "</groupId>\n");
					w.write("			<artifactId>" + dep.artifactId + "</artifactId>\n");
					w.write("			<version>" + toPomVersion(rd.getBundle().getVersion(), snapshotFilter.test(rd.getBundle())) + "</version>\n");
					if (rd.isOptional()) {
						w.write("			<optional>true</optional>\n");
					}
					w.write("		</dependency>\n");
				}
				if (!f.getResolvedFeatures().isEmpty()) {
					w.write("		<!-- Features -->\n");
					for (Feature rf : f.getResolvedFeatures()) {
						MavenDep dep = new MavenDep(featureGroupIdResolver.apply(rf), rf.getFeatureId());
						w.write("		<dependency>\n");
						w.write("			<groupId>" + dep.groupId + "</groupId>\n");
						w.write("			<artifactId>" + dep.artifactId + "</artifactId>\n");
						w.write("			<version>" + toPomVersion(rf.getVersion(), featureSnapshotFilter.test(rf)) + "</version>\n");
						w.write("		</dependency>\n");
					}
				}
				w.write("	</dependencies>\n");
			}
			w.write("</project>");
			w.close();
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private boolean exec(String[] cmdArray, FileOutputStream out) throws Throwable {
		if (dryRun) {
			out.write((Stream.of(cmdArray).collect(Collectors.joining(" ")) + "\n").getBytes());
			return true;
		} else {
			ProcessBuilder b = new ProcessBuilder(cmdArray);
			b.inheritIO();
			int waitFor = b.start().waitFor();
			return waitFor == 0;
		}
	}

	private void initMavenIndex() throws PlexusContainerException, ComponentLookupException,
			ExistingLuceneIndexMismatchException, IllegalArgumentException, IOException {
		if (this.indexer != null) {
			return;
		}

		final DefaultContainerConfiguration config = new DefaultContainerConfiguration();
		config.setClassPathScanning(PlexusConstants.SCANNING_INDEX);

		DefaultPlexusContainer plexusContainer = new DefaultPlexusContainer(config);
		Indexer indexer = plexusContainer.lookup(Indexer.class);
		IndexUpdater indexUpdater = plexusContainer.lookup(IndexUpdater.class);
		Wagon httpWagon = plexusContainer.lookup(Wagon.class, "http");

		File tempDir = Files.createTempDir();
		File cache = new File(tempDir, "repo-cache");
		File index = new File(tempDir, "repo-index");

		List<IndexCreator> indexers = new ArrayList<>();
		indexers.add(plexusContainer.lookup(IndexCreator.class, "min"));
		indexers.add(plexusContainer.lookup(IndexCreator.class, "jarContent"));
		indexers.add(plexusContainer.lookup(IndexCreator.class, "maven-plugin"));

		IndexingContext indexContext = indexer.createIndexingContext("repo-context", "repo", cache, index,
				repositoryUrl, null, true, true, indexers);

		ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, null, null, null);

		IndexUpdateRequest updateRequest = new IndexUpdateRequest(indexContext, resourceFetcher);
		IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
		if (!updateResult.isSuccessful()) {
			throw new RuntimeException("Failed to update index");
		}

		this.indexer = indexer;
		this.indexContext = indexContext;
	}

	private boolean isAvailable(Bundle bundle) {
		// System.err.println("SEARCHING " + groupIdResolver.apply(bundle) + ":" +
		// bundle.getBundleId() + ":" + bundle.getVersion());
		if (dryRun) {
			return false;
		}

		try {
			initMavenIndex();

			Query gQuery = indexer.constructQuery(MAVEN.GROUP_ID,
					new SourcedSearchExpression(groupIdResolver.apply(bundle)));
			Query aQuery = indexer.constructQuery(MAVEN.ARTIFACT_ID, new SourcedSearchExpression(bundle.getBundleId()));
			Query vQuery = indexer.constructQuery(MAVEN.VERSION,
					new SourcedSearchExpression(toPomVersion(bundle.getVersion(),false)));
			Query cQuery = indexer.constructQuery(MAVEN.CLASSIFIER, new SourcedSearchExpression(Field.NOT_PRESENT));

			BooleanQuery bq = new BooleanQuery();
			bq.add(gQuery, Occur.MUST);
			bq.add(aQuery, Occur.MUST);
			bq.add(vQuery, Occur.MUST);
			bq.add(cQuery, Occur.MUST_NOT);
			FlatSearchResponse response = indexer.searchFlat(new FlatSearchRequest(bq, indexContext));
			// System.err.println("=====> " + response.getResults().size());
			// System.exit(0);
			return response.getResults().size() > 0;
		} catch (Throwable e) {
			e.printStackTrace();
			return false;
		}
	}

	private boolean isAvailable(Feature feature) {
		// System.err.println("SEARCHING " + groupIdResolver.apply(bundle) + ":" +
		// bundle.getBundleId() + ":" + bundle.getVersion());
		if (dryRun) {
			return false;
		}

		try {
			initMavenIndex();

			Query gQuery = indexer.constructQuery(MAVEN.GROUP_ID,
					new SourcedSearchExpression(featureGroupIdResolver.apply(feature)));
			Query aQuery = indexer.constructQuery(MAVEN.ARTIFACT_ID,
					new SourcedSearchExpression(feature.getFeatureId()));
			Query vQuery = indexer.constructQuery(MAVEN.VERSION,
					new SourcedSearchExpression(toPomVersion(feature.getVersion(),false)));
			Query cQuery = indexer.constructQuery(MAVEN.CLASSIFIER, new SourcedSearchExpression(Field.NOT_PRESENT));

			BooleanQuery bq = new BooleanQuery();
			bq.add(gQuery, Occur.MUST);
			bq.add(aQuery, Occur.MUST);
			bq.add(vQuery, Occur.MUST);
			bq.add(cQuery, Occur.MUST_NOT);
			FlatSearchResponse response = indexer.searchFlat(new FlatSearchRequest(bq, indexContext));
			// System.err.println("=====> " + response.getResults().size());
			// System.exit(0);
			return response.getResults().size() > 0;
		} catch (Throwable e) {
			e.printStackTrace();
			return false;
		}
	}

	private boolean publish(Feature feature) throws Throwable {
		if (!featureSnapshotFilter.test(feature)) {
			if (isAvailable(feature)) {
				System.out.println("	Skipping '" + feature.getFeatureId() + "' because it is already uploaded");
				return true;
			}
		}

		System.out.print("	Publishing " + feature.getFeatureId() + " ... ");
		FileOutputStream out = new FileOutputStream(new File(workingDirectory, "publish-feature.sh"), true);
		out.write(("\n\necho 'Publishing " + feature.getFeatureId() + "'\n\n").getBytes());

		boolean rv = exec(new String[] { "mvn", "gpg:sign-and-deploy-file", "-Durl=" + repositoryUrl,
				"-DrepositoryId=" + repositoryId,
				"-DpomFile=" + new File(workingDirectory, "/poms/feature_" + feature.getFeatureId() + ".xml")
						.getAbsolutePath(),
				"-Dfile=" + new File(workingDirectory, feature.getFeatureId() + "_" + feature.getVersion() + ".jar")
						.getAbsolutePath() },
				out);
		if (!rv) {
			System.err.println("Failed to publish binary artifact - '" + feature.getFeatureId() + "'");
			return false;
		}
		out.close();
		System.out.println("done");
		return true;
	}

	private boolean publish(Bundle bundle) throws Throwable {
		if (!snapshotFilter.test(bundle)) {
			if (isAvailable(bundle)) {
				System.out.println("	Skipping '" + bundle.getBundleId() + "' because it is already uploaded");
				return true;
			}
		}

		System.out.print("	Publishing " + bundle.getBundleId() + " ... ");
		FileOutputStream out = new FileOutputStream(new File(workingDirectory, "publish.sh"), true);
		out.write(("\n\necho 'Publishing " + bundle.getBundleId() + "'\n\n").getBytes());

		String javadocDirectory = new File(workingDirectory, "javadoc_" + Thread.currentThread().getName())
				.getAbsolutePath();
		String sourceDirectory = new File(workingDirectory, "source_" + Thread.currentThread().getName())
				.getAbsolutePath();
		String javaDocJar = new File(workingDirectory, "javadoc_" + Thread.currentThread().getName() + ".jar")
				.getAbsolutePath();

		exec(new String[] { "rm", "-rf", javadocDirectory }, out);
		exec(new String[] { "rm", "-rf", sourceDirectory }, out);
		exec(new String[] { "rm", "-f", javaDocJar }, out);
		exec(new String[] { "mkdir", javadocDirectory }, out);
		exec(new String[] { "unzip", "-d", sourceDirectory,
				new File(workingDirectory, bundle.getBundleId() + ".source_" + bundle.getVersion() + ".jar")
						.getAbsolutePath() },
				out);
		exec(new String[] { "javadoc", "-d", javadocDirectory, "-sourcepath", sourceDirectory, "-subpackages", "." },
				out);

		exec(new String[] { "jar", "cf", javaDocJar, "-C", javadocDirectory + "/", "." }, out);
		boolean rv = exec(new String[] { "mvn", "gpg:sign-and-deploy-file", "-Durl=" + repositoryUrl,
				"-DrepositoryId=" + repositoryId,
				"-DpomFile=" + new File(workingDirectory, "/poms/" + bundle.getBundleId() + ".xml").getAbsolutePath(),
				"-Dfile=" + new File(workingDirectory, bundle.getBundleId() + "_" + bundle.getVersion() + ".jar")
						.getAbsolutePath() },
				out);
		if (!rv) {
			System.err.println("Failed to publish binary artifact - '" + bundle.getBundleId() + "'");
			return false;
		}

		if (new File(workingDirectory, bundle.getBundleId() + ".source_" + bundle.getVersion() + ".jar").exists()) {
			rv = exec(new String[] { "mvn", "gpg:sign-and-deploy-file", "-Durl=" + repositoryUrl,
					"-DrepositoryId=" + repositoryId,
					"-DpomFile="
							+ new File(workingDirectory, "/poms/" + bundle.getBundleId() + ".xml").getAbsolutePath(),
					"-Dfile=" + new File(workingDirectory,
							bundle.getBundleId() + ".source_" + bundle.getVersion() + ".jar").getAbsolutePath(),
					"-Dclassifier=sources" }, out);

			if (!rv) {
				System.err.println("ERROR: Failed to publish source artifact - '" + bundle.getBundleId() + "'");
				return false;
			}

			rv = exec(new String[] { "mvn", "gpg:sign-and-deploy-file", "-Durl=" + repositoryUrl,
					"-DrepositoryId=" + repositoryId,
					"-DpomFile="
							+ new File(workingDirectory, "/poms/" + bundle.getBundleId() + ".xml").getAbsolutePath(),
					"-Dfile=" + javaDocJar, "-Dclassifier=javadoc" }, out);

			if (!rv) {
				System.err.println("ERROR: Failed to publish javadoc artifact - '" + bundle.getBundleId() + "'");
				return false;
			}
		} else {
			System.err.println("ERROR: No source jar available");
			if (sourceEnforced.test(bundle)) {
				return false;
			}
		}

		exec(new String[] { "rm", "-rf", javadocDirectory }, out);
		exec(new String[] { "rm", "-rf", sourceDirectory }, out);
		exec(new String[] { "rm", "-f", javaDocJar }, out);

		out.close();
		System.out.println("done");

		return true;
	}

	private Predicate<Bundle> generatePoms(List<Bundle> bundleList, List<Feature> featureList) {
		bundleById = bundleList.stream().collect(Collectors.groupingBy(b -> b.getBundleId()));
		featureById = featureList.stream().collect(Collectors.groupingBy(f -> f.getFeatureId()));
		bundleExports = bundleList.stream().flatMap(i -> i.getExportPackages().stream()).collect(
				Collectors.groupingBy(e -> e.getName(), Collectors.mapping(e -> e.getBundle(), Collectors.toSet())));

		System.out.print("Resolving bundles ...");

		bundleList.stream().filter(bundleFilter).forEach(b -> b.resolve(this::resolve));

		System.out.println("done");

		System.out.print("Resolving features ...");

		featureList.stream().filter(featureFilter).forEach(f -> f.resolveBundles(this::resolveFeatureBundles));
		featureList.stream().filter(featureFilter).forEach(f -> f.resolveFeatures(this::resolveFeatureFeatures));

		System.out.println("done");

		System.out.print("Generated pom.xml files ...");

		Predicate<Bundle> publishFilter = b -> bundleById.containsKey(b.getBundleId() + ".source")
				|| !sourceEnforced.test(b); // only publish stuff we have the source available

		publishFilter = this.publishFilter.and(publishFilter).and(b -> !mavenReplacementLookup.apply(b).isPresent());

		bundleList.stream().filter(bundleFilter).filter(publishFilter).forEach(this::createPom);
		featureList.stream().filter(featureFilter).filter(featurePublishFilter).forEach(this::createPom);

		System.out.println("done");
		return publishFilter;
	}
	
	public void publishVersionModule(String groupId, String artifactId, String version, String propertyPrefix) throws Throwable {
		new File(workingDirectory, "poms").mkdirs();
		
		try (FileOutputStream out = new FileOutputStream(
				new File(new File(workingDirectory, "poms"), "version_module.xml"));
				OutputStreamWriter w = new OutputStreamWriter(out)) {
			writeLine(w,
					"<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
			writeLine(w,
					"	xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">");
			writeLine(w, "	<modelVersion>4.0.0</modelVersion>");
			writeLine(w, "	<groupId>"+groupId+"</groupId>");
			writeLine(w, "	<artifactId>"+artifactId+"</artifactId>");
			writeLine(w, "	<version>" + version + "</version>");
			writeLine(w, "	<packaging>pom</packaging>");
			
			List<Bundle> bundleList = generateBundleList();
			
			writeLine(w, "	<properties>");
			for( Bundle b : bundleList ) {
				if( b.getBundleId().startsWith("file:") ) {
					continue;
				}
				writeLine(w, "		<"+propertyPrefix+b.getBundleId()+">"+toPomVersion(b.getVersion(), snapshotFilter.test(b))+"</"+propertyPrefix+b.getBundleId()+">");
			}
			writeLine(w, "	</properties>");
			
			w.write("</project>");
			w.close();
		}
		
		FileOutputStream out = new FileOutputStream(new File(workingDirectory, "publish-feature.sh"), true);
		out.write(("\n\necho 'Publishing Version Bundle'\n\n").getBytes());
		
		boolean rv = exec(new String[] { "mvn", "gpg:sign-and-deploy-file", "-Durl=" + repositoryUrl,
				"-DrepositoryId=" + repositoryId,
				"-DpomFile=" + new File(workingDirectory, "/poms/version_module.xml").getAbsolutePath(),
				"-Dfile=" + new File(workingDirectory, "/poms/version_module.xml").getAbsolutePath()
				},
				out);
		if( ! rv ) {
			System.err.println("Failed to publish binary artifact - 'Version Bundle'");
		}
	}

	public void publish() throws Throwable {
		ExecutorService executorService = Executors.newFixedThreadPool(PUBLISHING_THREADS, r -> {
			Thread thread = new Thread(r, "publish_" + counter.incrementAndGet());
			return thread;
		});

		FileUtils.deleteDirectory(workingDirectory);
		new File(workingDirectory, "poms").mkdirs();
		new File(workingDirectory, "m2-repo").mkdirs();

		List<Bundle> bundleList = generateBundleList();
		List<Feature> featureList = generateFeatureList();

		Predicate<Bundle> publishFilter = generatePoms(bundleList, featureList);

		System.out.println("Publishing bundles ...");
		AtomicBoolean failure = new AtomicBoolean();
		bundleList.stream().filter(bundleFilter).filter(publishFilter) // only publish stuff we have the source
																		// available
				.forEach(b -> {
					executorService.execute(() -> {
						try {
							if (!publish(b)) {
								failure.set(true);
							}
						} catch (Throwable e) {
							e.printStackTrace();
							failure.set(true);
						}
					});
				});
		featureList.stream().filter(featureFilter) // only publish stuff we have the source
				// available
				.forEach(b -> {
					executorService.execute(() -> {
						try {
							if (!publish(b)) {
								failure.set(true);
							}
						} catch (Throwable e) {
							e.printStackTrace();
							failure.set(true);
						}
					});
				});
		executorService.shutdown();
		if (executorService.awaitTermination(4, TimeUnit.HOURS)) {
			System.out.println("done");
		} else {
			System.out.println("Publishing took too long killing it");
			executorService.shutdownNow();
		}

		if (failure.get()) {
			System.out.println("Failed to publish all bundles. Exiting ...");
			System.exit(1);
		}

		if (!keepGenFolder) {
			FileUtils.deleteDirectory(workingDirectory);
		}
	}

	public void validate() throws Throwable {
		if (!keepGenFolder) {
			FileUtils.deleteDirectory(workingDirectory);
		}
		
		new File(workingDirectory, "poms").mkdirs();
		new File(workingDirectory, "m2-repo").mkdirs();

		List<Bundle> bundleList = generateBundleList();
		List<Feature> featureList = generateFeatureList();
		Predicate<Bundle> publishFilter = generatePoms(bundleList, featureList);

		System.out.print("Validation bundles ...");
		{
			List<Bundle> failures = bundleList.stream().filter(bundleFilter).filter(publishFilter) // only publish stuff
																									// we
					// have the source
					// available
					.filter(((Predicate<Bundle>) this::validate).negate()).collect(Collectors.toList());

			if (failures.isEmpty()) {
				System.out.println("done");
			} else {
				System.out.println();
				failures.forEach(b -> System.out.println("Resolve failure for '" + b.getBundleId() + "'"));
			}

		}

		List<Feature> failures = featureList.stream().filter(featureFilter)
				.filter(((Predicate<Feature>) this::validate).negate()).collect(Collectors.toList());

		if (failures.isEmpty()) {
			System.out.println("done");
		} else {
			System.out.println();
			failures.forEach(b -> System.out.println("Resolve failure for '" + b.getFeatureId() + "'"));
		}

		if (!keepGenFolder) {
			FileUtils.deleteDirectory(workingDirectory);
		} else {
			System.err.println(workingDirectory);
		}
	}

	private boolean validate(Bundle bundle) {
		try {
			FileOutputStream out = new FileOutputStream(new File(workingDirectory, "validate.sh"), true);
			return exec(new String[] { "mvn", "dependency:tree", "-f",
					new File(workingDirectory, "/poms/" + bundle.getBundleId() + ".xml").getAbsolutePath(),
					"-Posgi-validate" }, out);
		} catch (Throwable t) {

		}
		return false;
	}
	
	private boolean validate(Feature bundle) {
		try {
			FileOutputStream out = new FileOutputStream(new File(workingDirectory, "validate.sh"), true);
			return exec(new String[] { "mvn", "dependency:tree", "-f",
					new File(workingDirectory, "/poms/feature_" + bundle.getFeatureId() + ".xml").getAbsolutePath(),
					"-Posgi-validate" }, out);
		} catch (Throwable t) {

		}
		return false;
	}

	public abstract List<Bundle> generateBundleList() throws Throwable;

	public abstract List<Feature> generateFeatureList() throws Throwable;
}
