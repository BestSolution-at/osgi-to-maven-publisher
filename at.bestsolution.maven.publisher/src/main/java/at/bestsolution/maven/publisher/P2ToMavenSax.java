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


import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.io.FileUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * P2 to maven publisher
 */
public class P2ToMavenSax extends OsgiToMaven {
	private String indexZip;

    public P2ToMavenSax(String indexZip, String repositoryUrl, String repositoryId) {
    	super(repositoryUrl, repositoryId);
		this.indexZip = indexZip;
	}

	@Override
	public List<Bundle> generateBundleList() throws Throwable {
		unzipRepository(new File(indexZip),workingDirectory);
		unzipRepository(new File(workingDirectory,"content.jar"), workingDirectory);
		FileUtils.copyDirectory(new File(workingDirectory,"plugins"), workingDirectory);

		SAXParserFactory instance = SAXParserFactory.newInstance();
		SAXParser parser = instance.newSAXParser();
		SaxHandlerImpl dh = new SaxHandlerImpl();
		parser.parse(new FileInputStream(new File(workingDirectory,"content.xml")), dh);

		return dh.bundles;
	}

	static class SaxHandlerImpl extends DefaultHandler {
		private Bundle currentBundle;
		private List<Bundle> bundles = new ArrayList<>();
		private boolean inUnit;
		private boolean inProvides;
		private boolean inRequires;

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			if( qName.equals("unit") ) {
				inUnit = true;
				currentBundle = new Bundle();
				currentBundle.setBundleId(attributes.getValue("id"));
				currentBundle.setVersion(attributes.getValue("version"));
			} else if( inUnit && qName.equals("provides") ) {
				inProvides = true;
			} else if( inUnit && qName.equals("requires") ) {
				inRequires = true;
			} else if( inProvides && qName.equals("provided") ) {
				if( attributes.getValue("namespace").equals("java.package") ) {
					ExportPackage p = new ExportPackage();
					p.setName(attributes.getValue("name"));
					p.setVersion(attributes.getValue("version"));
					currentBundle.addExport(p);
				} else if( attributes.getValue("namespace").equals("osgi.fragment") ) {
					Fragment f = new Fragment();
					f.setBundleId(currentBundle.getBundleId());
					f.setVersion(currentBundle.getVersion());
					currentBundle.getExportPackages().forEach( f::addExport);
					currentBundle.getImportPackages().forEach( f::addImport);
					currentBundle.getRequiredBundles().forEach( f::addRequire);
					currentBundle = f;
				}
			} else if( inRequires && qName.equals("required") ) {
				if( attributes.getValue("namespace").equals("java.package") ) {
					ImportPackage ip = new ImportPackage();
					ip.setName(attributes.getValue("name"));
					ip.setOptional("true".equals(attributes.getValue("optional")));
					currentBundle.addImport(ip);
				} else if( attributes.getValue("namespace").equals("osgi.bundle") ) {
					RequireBundle rb = new RequireBundle();
					rb.setName(attributes.getValue("name"));
					rb.setOptional("true".equals(attributes.getValue("optional")));
					currentBundle.addRequire(rb);
				}
			} else if( currentBundle != null && qName.equals("property") ) {
				if( "df_LT.bundleVendor".equals(attributes.getValue("name"))
						|| "df_LT.Bundle-Vendor".equals(attributes.getValue("name"))
						|| "df_LT.providerName".equals(attributes.getValue("name"))) {
					currentBundle.setVendor(attributes.getValue("value"));
				} else if( "df_LT.bundleName".equals(attributes.getValue("name"))
						|| "df_LT.Bundle-Name".equals(attributes.getValue("name"))
						|| "df_LT.pluginName".equals(attributes.getValue("name"))) {
					currentBundle.setName(attributes.getValue("value"));
				}
			}

			super.startElement(uri, localName, qName, attributes);
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			if( qName.equals("unit") ) {
				if( currentBundle != null ) {
					bundles.add(currentBundle);
				}
				currentBundle = null;
				inUnit = false;
			} else if( qName.equals("provides") ) {
				inProvides = false;
			} else if( qName.equals("requires") ) {
				inRequires = false;
			}
			super.endElement(uri, localName, qName);
		}
	}
}
