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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Represents an OSGi-Bundle
 */
public class Bundle {
	private String bundleId;
	private String name;
	private String vendor;
	private String version;

	private List<ExportPackage> exportPackages = new ArrayList<>();
	private List<ImportPackage> importPackages = new ArrayList<>();
	private List<RequireBundle> requiredBundles = new ArrayList<>();

	private List<ResolvedBundle> resolvedBundleDeps;

	public Bundle() {
		// TODO Auto-generated constructor stub
	}

	public String getBundleId() {
		return bundleId;
	}

	public void setBundleId(String bundleId) {
		this.bundleId = bundleId;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void setVendor(String vendor) {
		this.vendor = vendor;
	}

	public String getVendor() {
		return vendor;
	}

	public List<ExportPackage> getExportPackages() {
		return exportPackages;
	}

	public List<ImportPackage> getImportPackages() {
		return importPackages;
	}

	public List<RequireBundle> getRequiredBundles() {
		return requiredBundles;
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder();
		b.append("bundle " + bundleId + "@" + version + "{\n");
		b.append("	exports {\n");
		b.append(exportPackages.stream().map(e -> e.getName() + "@" + e.getVersion())
				.collect(Collectors.joining("\n		", "		", "\n")));
		b.append("	}\n");
		b.append("	imports {\n");
		b.append(importPackages.stream().map(e -> (e.isOptional() ? "optional " : "") + e.getName())
				.collect(Collectors.joining("\n		", "		", "\n")));
		b.append("	}\n");
		b.append("	require-bundle {\n");
		b.append(requiredBundles.stream().map(e -> (e.isOptional() ? "optional " : "") + e.getName())
				.collect(Collectors.joining("\n		", "		", "\n")));
		b.append("	}\n");
		b.append("}");
		return b.toString();
	}

	public void addExport(ExportPackage currentExport) {
		exportPackages.add(currentExport);
		currentExport.setBundle(this);
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		// if( this.bundleId.equals("org.eclipse.fx.osgi.util") ) {
		// R5ToMavenSax.DEBUG = !R5ToMavenSax.DEBUG;
		// }
		this.version = version;
	}

	public void addImport(ImportPackage currentImport) {
		importPackages.add(currentImport);
	}

	public void addRequire(RequireBundle currentRequireBundle) {
		requiredBundles.add(currentRequireBundle);
	}

	public void resolve(Function<Bundle, Set<ResolvedBundle>> resolver) {
		if (resolvedBundleDeps == null) {
			resolvedBundleDeps = new ArrayList<ResolvedBundle>(resolver.apply(this));
			resolvedBundleDeps.removeIf( r -> Objects.equals(getBundleId(),r.getBundle().getBundleId()));
			Collections.sort(resolvedBundleDeps, new Comparator<ResolvedBundle>() {
				@Override
				public int compare(ResolvedBundle o1, ResolvedBundle o2) {
					return o1.getBundle().getBundleId().compareTo(o2.getBundle().getBundleId());
				}
			});
		}
	}

	public List<ResolvedBundle> getResolvedBundleDeps() {
		return resolvedBundleDeps == null ? Collections.emptyList() : Collections.unmodifiableList(resolvedBundleDeps);
	}
}
