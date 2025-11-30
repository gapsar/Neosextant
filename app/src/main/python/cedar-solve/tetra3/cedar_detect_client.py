# Copyright (c) 2024 Steven Rosenthal smr@dt3.org
# See LICENSE file in root directory for license terms.

from __future__ import annotations
import logging
import subprocess
import time
from pathlib import Path
from typing import Union

import grpc
# from multiprocessing import shared_memory # Shared memory not supported on Android
import numpy as np

from tetra3 import cedar_detect_pb2, cedar_detect_pb2_grpc

_bin_dir = Path(__file__).parent / "bin"


class CedarDetectClient:
    """Executes the cedar-detect-server binary as a subprocess. That binary is a
    gRPC server described by the tetra3/proto/cedar_detect.proto file.
    """

    def __init__(self, logger = None, binary_path: Union[Path, str, None] = None, port=50051):
        """Spawns the cedar-detect-server subprocess.

        Args:
            logger: If have a logger object, pass it in here. Otherwise one will be created
                locally.
            binary_path: If you wish to specify a custom location for the `cedar-detect-server` binary you
                may do so, otherwise the default is to search in the relative directory "./bin"
            port: Customize the `cedar-detect-server` port if running multiple instances.
        """
        if logger is None:
            self._logger = logging.getLogger('CedarDetectClient')
            # Add new handlers to the logger.
            self._logger.setLevel(logging.DEBUG)
            # Console handler at INFO level
            ch = logging.StreamHandler()
            ch.setLevel(logging.INFO)
            ch.setFormatter(
                logging.Formatter('%(asctime)s:%(name)s-%(levelname)s: %(message)s'))
            self._logger.addHandler(ch)
        else:
            self._logger = logger

        # On Android, we must use the native library directory to execute binaries
        try:
            from com.chaquo.python import Python
            context = Python.getPlatform().getApplication()
            native_lib_dir = context.getApplicationInfo().nativeLibraryDir
            # The binary must be named lib<name>.so to be extracted to nativeLibraryDir
            self._binary_path = Path(native_lib_dir) / "libcedar_detect_server.so"
            self._logger.info(f"Using native library path: {self._binary_path}")
        except ImportError:
            # Fallback for non-Android environments
            self._binary_path: Path = Path(binary_path) if binary_path else _bin_dir / "cedar-detect-server"

        if not self._binary_path.exists() or not self._binary_path.is_file():
            # Try fallback to local bin if native lib not found (e.g. during dev/test on PC)
             fallback_path = _bin_dir / "cedar-detect-server"
             if fallback_path.exists():
                 self._binary_path = fallback_path
             else:
                 raise ValueError(f"The cedar-detect-server binary could not be found at '{self._binary_path}' or '{fallback_path}'.")
        
        # Ensure the binary is executable (chmod +x might still be needed even in native lib dir on some devices)
        import os
        import stat
        try:
            st = os.stat(self._binary_path)
            self._logger.info(f"Binary stats: size={st.st_size}, mode={oct(st.st_mode)}")
            os.chmod(self._binary_path, st.st_mode | stat.S_IEXEC)
        except Exception as e:
            self._logger.warning(f"Failed to set executable permission on {self._binary_path}: {e}")

        self._port = port

        self._logger.info(f"Executing subprocess: {self._binary_path} --port {self._port}")
        self._subprocess = subprocess.Popen([str(self._binary_path), '--port', str(self._port)], stderr=subprocess.PIPE)
        # Will initialize on first use.
        self._stub = None
        self._shmem = None
        self._shmem_size = 0
        # Try shared memory, fall back if an error occurs.
        self._use_shmem = False # Shared memory disabled for Android

    def __del__(self):
        if hasattr(self, '_subprocess') and self._subprocess:
            self._subprocess.kill()
        self._del_shmem()

    def _get_stub(self):
        if self._stub is None:
            self._stub = cedar_detect_pb2_grpc.CedarDetectStub(
                grpc.insecure_channel(
                    f'localhost:{self._port}',
                    options=[
                        ('grpc.max_send_message_length', 20 * 1024 * 1024),
                        ('grpc.max_receive_message_length', 20 * 1024 * 1024),
                    ]
                )
            )
        return self._stub

    # Returns True if the shared memory file was re-created with a new size.
    def _alloc_shmem(self, size):
        return False # Shared memory disabled

    def _del_shmem(self):
        pass # Shared memory disabled

    def extract_centroids(self, image, sigma, max_size, use_binned, binning=None,
                          detect_hot_pixels=True):
        """Invokes the CedarDetect.ExtractCentroids() RPC. Returns [(y,x)] of the
        detected star centroids.
        """
        np_image = np.asarray(image, dtype=np.uint8)
        (height, width) = np_image.shape

        centroids_result = None
        im = None
        rpc_exception = None
        retried = False
        while True:
            if rpc_exception is not None:
                # See if subprocess exited. If so, we restart it and retry once.
                returncode = self._subprocess.poll()
                if returncode is None:
                    # Subprocess still there; just propagate the exception.
                    raise rpc_exception
                self._logger.error('Subprocess exit code: %s' % returncode)
                # No longer reading from stderr pipe, output is redirected to file
                if self._subprocess.poll() is not None:
                    # Process exited unexpectedly
                    self._logger.warning("CedarDetectServer process exited unexpectedly with code %s", self._subprocess.returncode)
                    try:
                        with open(self._log_file_path, "r") as f:
                            err_out = f.read()
                        self._logger.warning("CedarDetectServer output:\n%s", err_out)
                    except Exception as e:
                        self._logger.warning("Could not read server log file: %s", e)
                
                # Close the log file
                try:
                    self._log_file.close()
                except:
                    pass
                self._logger.error(f"Subprocess stderr/stdout redirected to: {self._log_file_path}")
                if retried:
                    # We already retried once, bail.
                    raise rpc_exception
                retried = True
                rpc_exception = None
                self._logger.error('Creating new subprocess')
                # Redirect stderr to a file to avoid SIGSEGV in _posixsubprocess with PIPE
                # Close existing log file before reopening for new process
                if self._log_file:
                    self._log_file.close()
                self._log_file = open(self._log_file_path, "a") # Append to existing log
                
                self._subprocess = subprocess.Popen(
                    [str(self._binary_path), '--port', str(self._port)],
                    stderr=self._log_file,
                    stdout=self._log_file, # Also redirect stdout for debugging
                    close_fds=True
                )
                self._stub = None

            if self._use_shmem:
                 # Shared memory logic removed
                 pass

            if not self._use_shmem:
                # Not using shared memory. The image data is passed as part of the
                # gRPC request.
                im = cedar_detect_pb2.Image(width=width, height=height,
                                            image_data=np_image.tobytes())
                req = cedar_detect_pb2.CentroidsRequest(
                    input_image=im, sigma=sigma, max_size=max_size, return_binned=False,
                    binning=binning, use_binned_for_star_candidates=use_binned,
                    detect_hot_pixels=detect_hot_pixels)
                try:
                    centroids_result = self._get_stub().ExtractCentroids(req)
                    break  # Succeeded, break out of retry loop.
                except grpc.RpcError as err:
                    self._logger.error('RPC failed with: %s' % err.details())
                    rpc_exception = err  # Loop to retry logic.
        # while True

        tetra_centroids = []  # List of (y, x).
        if centroids_result is not None:
            for sc in centroids_result.star_candidates:
                tetra_centroids.append((sc.centroid_position.y, sc.centroid_position.x))
        return tetra_centroids
