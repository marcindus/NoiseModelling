package org.noise_planet.noisemodelling.propagation.jdbc;

import org.h2.util.StringUtils;
import org.h2gis.api.EmptyProgressVisitor;
import org.h2gis.functions.factory.H2GISDBFactory;
import org.h2gis.utilities.SFSUtilities;
import org.h2gis.utilities.SpatialResultSet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.locationtech.jts.geom.Geometry;
import org.noise_planet.noisemodelling.propagation.ComputeRays;
import org.noise_planet.noisemodelling.propagation.ComputeRaysOut;
import org.noise_planet.noisemodelling.propagation.EvaluateAttenuationCnossosTest;
import org.noise_planet.noisemodelling.propagation.FastObstructionTest;
import org.noise_planet.noisemodelling.propagation.IComputeRaysOut;
import org.noise_planet.noisemodelling.propagation.PropagationPath;
import org.noise_planet.noisemodelling.propagation.PropagationProcessData;
import org.noise_planet.noisemodelling.propagation.PropagationProcessPathData;
import org.noise_planet.noisemodelling.propagation.PropagationResultPtRecord;
import org.noise_planet.noisemodelling.propagation.QueryRTree;

import java.io.File;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class PointNoiseMapTest {

    private static Connection connection;

    @BeforeClass
    public static void tearUp() throws Exception {
        connection = SFSUtilities.wrapConnection(H2GISDBFactory.createSpatialDataBase(PointNoiseMapTest.class.getSimpleName(), true, ""));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if(connection != null) {
            connection.close();
        }
    }

    private static String getRunScriptRes(String fileName) throws URISyntaxException {
        File resourceFile = new File(PointNoiseMapTest.class.getResource(fileName).toURI());
        return "RUNSCRIPT FROM "+StringUtils.quoteStringSQL(resourceFile.getPath());
    }

    @Test
    public void testSplitting() {
        PointNoiseMap pointNoiseMap = new PointNoiseMap("BUILDINGS", "ROADS", "RECEIVERS");

    }

    public void testLdenPointNoiseMap() {
        // Test optimisation on lden scenario
        PointNoiseMap pointNoiseMap = new PointNoiseMap("BUILDINGS", "ROADS", "RECEIVERS");
    }


    /**
     * DEM is 22m height between sources and receiver. There is a direct field propagation over the building
     * @throws SQLException
     */
    @Test
    public void testDemTopOfBuilding() throws Exception {
        try(Statement st = connection.createStatement()) {
            st.execute(getRunScriptRes("scene_with_dem.sql"));
            st.execute("DROP TABLE IF EXISTS RECEIVERS");
            st.execute("CREATE TABLE RECEIVERS(the_geom POINT, GID SERIAL)");
            st.execute("INSERT INTO RECEIVERS(the_geom) VALUES ('POINT(-72 41 11)')");
            st.execute("INSERT INTO RECEIVERS(the_geom) VALUES ('POINT(-9 41 1.6)')");
            st.execute("INSERT INTO RECEIVERS(the_geom) VALUES ('POINT(70 11 7)')");
            PointNoiseMap pointNoiseMap = new PointNoiseMap("BUILDINGS", "SOUND_SOURCE", "RECEIVERS");
            pointNoiseMap.setComputeHorizontalDiffraction(true);
            pointNoiseMap.setComputeVerticalDiffraction(true);
            pointNoiseMap.setSoundReflectionOrder(0);
            pointNoiseMap.setHeightField("HEIGHT");
            pointNoiseMap.setDemTable("DEM");
            pointNoiseMap.setComputeVerticalDiffraction(false);
            pointNoiseMap.initialize(connection, new EmptyProgressVisitor());
            pointNoiseMap.setComputeRaysOutFactory(new JDBCComputeRaysOut());
            pointNoiseMap.setPropagationProcessDataFactory(new JDBCPropagationData());
            List<ComputeRaysOut.verticeSL> allLevels = new ArrayList<>();
            Set<Long> receivers = new HashSet<>();
            pointNoiseMap.setThreadCount(1);
            for(int i=0; i < pointNoiseMap.getGridDim(); i++) {
                for(int j=0; j < pointNoiseMap.getGridDim(); j++) {
                    IComputeRaysOut out = pointNoiseMap.evaluateCell(connection, i, j, new EmptyProgressVisitor(), receivers);
                    if(out instanceof ComputeRaysOut) {
                        allLevels.addAll(((ComputeRaysOut) out).getVerticesSoundLevel());
                    }
                }
            }

            assertEquals(3, allLevels.size());
        }
    }

    private static class JDBCPropagationData implements PointNoiseMap.PropagationProcessDataFactory {
        @Override
        public PropagationProcessData create(FastObstructionTest freeFieldFinder) {
            return new DirectPropagationProcessData(freeFieldFinder);
        }
    }

    private static class JDBCComputeRaysOut implements PointNoiseMap.IComputeRaysOutFactory {
        @Override
        public IComputeRaysOut create(PropagationProcessData threadData, PropagationProcessPathData pathData) {
            return new RayOut(false, pathData, (DirectPropagationProcessData)threadData);
        }
    }

    private static class RayOut extends ComputeRaysOut {
        private DirectPropagationProcessData processData;

        public RayOut(boolean keepRays, PropagationProcessPathData pathData, DirectPropagationProcessData processData) {
            super(keepRays, pathData, processData);
            this.processData = processData;
        }

        @Override
        public double[] computeAttenuation(PropagationProcessPathData pathData, long sourceId, double sourceLi, long receiverId, List<PropagationPath> propagationPath) {
            double[] attenuation = super.computeAttenuation(pathData, sourceId, sourceLi, receiverId, propagationPath);
            double[] soundLevel = ComputeRays.wToDba(ComputeRays.multArray(processData.wjSources.get((int)sourceId), ComputeRays.dbaToW(attenuation)));
            return soundLevel;
        }
    }

    private static class DirectPropagationProcessData extends PropagationProcessData {
        private List<double[]> wjSources = new ArrayList<>();
        private final static String[] powerColumns = new String[]{"db_m63", "db_m125", "db_m250", "db_m500", "db_m1000", "db_m2000", "db_m4000", "db_m8000"};

        public DirectPropagationProcessData(FastObstructionTest freeFieldFinder) {
            super(freeFieldFinder);
        }


        @Override
        public void addSource(Long pk, Geometry geom, SpatialResultSet rs)  throws SQLException {
            super.addSource(pk, geom, rs);
            double sl[] = new double[powerColumns.length];
            int i = 0;
            for(String columnName : powerColumns) {
                sl[i++] = ComputeRays.dbaToW(rs.getDouble(columnName));
            }
            wjSources.add(sl);
        }

        @Override
        public double[] getMaximalSourcePower(int sourceId) {
            return wjSources.get(sourceId);
        }
    }
}