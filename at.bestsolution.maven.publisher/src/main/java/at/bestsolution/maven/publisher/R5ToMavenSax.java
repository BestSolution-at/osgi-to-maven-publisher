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

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * r5 to maven publisher
 */
public class R5ToMavenSax extends OsgiToMaven {
	static boolean DEBUG = false;
	static enum Context {
		OSGI_IDENTITY,
		OSGI_EXPORT_PACKAGE, OSGI_IMPORT_PACKAGE, OSGI_REQUIRE_BUNDLE
	}

	static class SaxHandlerImpl extends DefaultHandler {
		private List<Bundle> bundles = new ArrayList<>();
		private Bundle currentBundle;
		private ExportPackage currentExport;
		private ImportPackage currentImport;
		private RequireBundle currentRequireBundle;
		private boolean inResource;
		private Context context;
		private String name;


		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			if( DEBUG ) {
				System.err.println( "IN: " + qName );
				for( int i = 0; i < attributes.getLength(); i++ ) {
					System.err.println(" - " + attributes.getQName(i) + " = " + attributes.getValue(i));
				}
			}

			if( qName.equals("resource") ) {
				inResource = true;
			} else if( inResource && qName.equals("capability") ) {
				if( "osgi.identity".equals(attributes.getValue("namespace")) ) {
					context = Context.OSGI_IDENTITY;
				} else if( "osgi.wiring.package".equals(attributes.getValue("namespace")) ) {
					context = Context.OSGI_EXPORT_PACKAGE;
					currentExport = new ExportPackage();
					if( currentBundle != null ) {
						currentBundle.addExport(currentExport);
					}
				}
			} else if( inResource && qName.equals("requirement") ) {
				if( "osgi.wiring.package".equals(attributes.getValue("namespace")) ) {
					context = Context.OSGI_IMPORT_PACKAGE;
					currentImport = new ImportPackage();
					if( currentBundle != null ) {
						currentBundle.addImport(currentImport);
					}
				} else if( "osgi.wiring.bundle".equals(attributes.getValue("namespace")) ) {
					context = Context.OSGI_REQUIRE_BUNDLE;
					currentRequireBundle = new RequireBundle();
					if( currentBundle != null ) {
						currentBundle.addRequire(currentRequireBundle);
					}
				}
			} else if( context == Context.OSGI_IDENTITY && qName.equals("attribute") ) {
				if( "type".equals(attributes.getValue("name")) ) {
					if( "osgi.bundle".equals(attributes.getValue("value")) ) {
						currentBundle = new Bundle();
						currentBundle.setBundleId(name);
					} else if( "osgi.fragment".equals(attributes.getValue("value")) ) {
						currentBundle = new Fragment();
						currentBundle.setBundleId(name);
//						System.err.println("NAME: " + name);
					}
				} else if( "osgi.identity".equals(attributes.getValue("name")) ) {
					name = attributes.getValue("value");
				} else if( "version".equals(attributes.getValue("name")) ) {
					currentBundle.setVersion(attributes.getValue("value"));
				}
			} else if( context == Context.OSGI_EXPORT_PACKAGE && qName.equals("attribute") ) {
				if( "osgi.wiring.package".equals(attributes.getValue("name")) ) {
					currentExport.setName(attributes.getValue("value"));
				} else if( "version".equals(attributes.getValue("name")) ) {
					currentExport.setVersion(attributes.getValue("value"));
				}
			} else if( context == Context.OSGI_IMPORT_PACKAGE && qName.equals("directive") ) {
				if( "filter".equals(attributes.getValue("name")) ) {
					String filter = attributes.getValue("value");
					currentImport.setName(filter.substring(filter.indexOf("osgi.wiring.package=")+"osgi.wiring.package=".length(),filter.indexOf(')')));
				} else if( "resolution".equals(attributes.getValue("name")) ) {
					currentImport.setOptional("optional".equals(attributes.getValue("value")));
				}
			} else if( context == Context.OSGI_REQUIRE_BUNDLE && qName.equals("directive") ) {
				if( "filter".equals(attributes.getValue("name")) ) {
					String filter = attributes.getValue("value");
					currentRequireBundle.setName(filter.substring(filter.indexOf("osgi.wiring.bundle=")+"osgi.wiring.bundle=".length(),filter.indexOf(')')));
				} else if( "resolution".equals(attributes.getValue("name")) ) {
					currentRequireBundle.setOptional("optional".equals(attributes.getValue("value")));
				}
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			super.endElement(uri, localName, qName);
			if( DEBUG ) {
				System.err.println( "OUT: " + qName);
			}

			if( inResource && qName.equals("resource") ) {
				inResource = false;
				if( currentBundle != null ) {
					bundles.add(currentBundle);
				}
				currentBundle = null;
				context = null;
			} else if( qName.equals("capability") || qName.equals("requirement") ) {
				context = null;
			}
		}
	}

	private String indexZip;

    public R5ToMavenSax(String indexZip, String repositoryUrl, String repositoryId) {
    	super(repositoryUrl,repositoryId);
		this.indexZip = indexZip;
	}

    @Override
    public List<Bundle> generateBundleList() throws Throwable {
    	unzipRepository(new File(indexZip),file);
		SAXParserFactory instance = SAXParserFactory.newInstance();
		SAXParser parser = instance.newSAXParser();
		SaxHandlerImpl dh = new SaxHandlerImpl();
		parser.parse(new GzipCompressorInputStream(new FileInputStream(new File(file,"repository.xml.gz"))), dh);

    	return dh.bundles;
    }
}
